package com.newoether.agora.automation

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.repository.*
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.SubagentCompletion
import com.newoether.agora.model.SubagentSummary
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Owns delegation and the result outbox; the ordinary engine/mailbox still owns every Run. */
class SubagentManager(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val transfers: ConversationSettingsTransferCoordinator,
    private val engine: TaskExecutionEngine,
    private val registry: ConversationStateRegistry,
    private val coordinator: ConversationExecutionCoordinator,
    private val gate: AutomationExecutionGate,
    private val scope: CoroutineScope,
    private val enqueueCompletions: suspend (String) -> Unit,
    private val enqueueMessage: suspend (String, String, GenerationContext) -> String,
    private val stopLoop: suspend (String) -> Unit = {},
) {
    init {
        conversations.onSubagentTerminal = ::conversationIdle
        conversations.prepareConversationDeletion = { id ->
            val children = conversations.getSubagentIds(id)
            val owned = children + id
            synchronized(deliveryMonitor) {
                owned.forEach { deliveries[it]?.cancel(); dirtyParents.remove(it) }
            }
            owned.forEach { child -> cancel(child); stopLoop(child); registry.remove(child) }
        }
    }

    private val jobs = conversations.subagentJobs
    private val deliveryMonitor = Any()
    private val deliveries = mutableMapOf<String, Job>()
    private val dirtyParents = mutableSetOf<String>()

    suspend fun spawn(name: String, modelId: String, task: String, ctx: GenerationContext): String {
        val identity = authorized(ctx)
        require(name.isNotBlank() && task.isNotBlank()) { "name and task are required" }
        require(modelId in ctx.subagentModelIds.orEmpty()) {
            "model_id must be an allowed enabled model ID from this request"
        }
        settings.awaitInitialLoad()
        require(modelId in settings.getSubagentModelIds()) {
            "The selected model is no longer enabled or allowed for subagents"
        }
        val parent = checkNotNull(conversations.getConversation(identity.conversationId))
        val id = UUID.randomUUID().toString()
        val child = ChatEntity(
            id = id, title = name.trim(), modelId = modelId,
            systemPromptId = parent.systemPromptId, origin = "subagent",
            parentConversationId = parent.id,
        )
        conversations.createSubagent(
            identity, child,
            settings.conversationSettings.value[parent.id]?.let { Json.encodeToString(it) },
            maxSubagents = settings.getSubagentMaxTotal(),
        )
        launch(child, task, ctx)
        return id
    }

    suspend fun send(id: String, text: String, ctx: GenerationContext): String {
        val identity = authorized(ctx)
        require(text.isNotBlank()) { "message is required" }
        ownedChild(identity, id)
        return enqueueMessage(id, text, ctx)
    }

    private fun launch(child: ChatEntity, task: String, ctx: GenerationContext) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var releaseAdmission: (() -> Unit)? = null
            var completion: SubagentCompletion? = null
            var previousMessageId: String? = null
            try {
                releaseAdmission = coordinator.acquireGenerationAdmission(child.id)
                gate.withExecution {
                    coordinator.withAutomationConversationLock(child.id) {
                        transfers.complete(child.id)
                        conversations.recoverConversationRuntime(child.id)
                        previousMessageId = registry.get(child.id)?.streamingMessage?.value?.id
                        val result = engine.runOnceWithAutomationGuardsHeld(
                            conversationId = child.id, userText = task, modelId = child.modelId,
                            requestKind = "subagent", contextOverride = ctx,
                        )
                        completion = terminalCompletion(child.id, result, previousMessageId)
                        completion?.let {
                            conversations.recordSubagentCompletion(child.id, checkNotNull(child.parentConversationId), it)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    if (completion == null && conversations.getLiveRun(child.id) == null) {
                        completion = SubagentCompletion(UUID.randomUUID().toString(), "stopped", "Subagent stopped")
                    }
                    completion?.let {
                        conversations.recordSubagentCompletion(child.id, checkNotNull(child.parentConversationId), it)
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                // A failure after terminal settlement must not replace that exact result.
                if (completion == null && conversations.getLiveRun(child.id) == null) {
                    val detail = error.message ?: "Subagent failed"
                    completion = SubagentCompletion(UUID.randomUUID().toString(), "failed", detail,
                        errorText = detail)
                }
                completion?.let {
                    conversations.recordSubagentCompletion(child.id, checkNotNull(child.parentConversationId), it)
                }
            } finally {
                try {
                    if (completion != null) requestDelivery(checkNotNull(child.parentConversationId))
                } finally {
                    releaseAdmission?.invoke()
                }
            }
        }
        val previous = jobs.getAndUpdate { current ->
            if (child.id in current) current else current + (child.id to job)
        }
        check(child.id !in previous) {
            job.cancel()
            "Subagent is already running"
        }
        job.invokeOnCompletion {
            jobs.update { current -> if (current[child.id] === job) current - child.id else current }
        }
        job.start()
    }

    suspend fun list(ctx: GenerationContext): List<SubagentSummary> {
        val identity = authorized(ctx)
        return conversations.observeSubagents(identity.conversationId).first().map { child ->
            if (child.status != "failed") return@map child
            val completion = conversations.subagentFailureDetail(identity.conversationId, child.id)
            child.copy(errorText = completion?.errorText, errorCode = completion?.errorCode)
        }
    }

    suspend fun wait(ids: List<String>, timeoutMs: Long, ctx: GenerationContext): List<Pair<String, SubagentCompletion>> {
        val identity = authorized(ctx)
        val selected = ids.ifEmpty { conversations.getSubagentIds(identity.conversationId) }.distinct()
        selected.forEach { ownedChild(identity, it) }
        withTimeoutOrNull(timeoutMs.coerceIn(1, 300_000)) {
            selected.forEach { id ->
                jobs.value[id]?.join()
                registry.get(id)?.awaitSendAvailable()
            }
        }
        authorized(ctx)
        val results = conversations.pendingSubagentResults(identity.conversationId)
            .filter { it.id in selected && jobs.value[it.id] == null }
            .map { it.id to checkNotNull(SubagentCompletion.decode(it.resultJson)) }
        // Receipts are acknowledged only when the ordinary tool round commits its result rows.
        return results
    }

    suspend fun stop(id: String, ctx: GenerationContext) {
        val identity = authorized(ctx)
        ownedChild(identity, id)
        cancel(id)
        authorized(ctx)
    }

    suspend fun cancel(id: String) {
        val job = jobs.value[id]
        job?.cancel()
        engine.stopConversationAndAwait(id)
        job?.join()
    }

    /** Only the explicitly opened owner or a process-admitted idle event may activate delivery. */
    fun conversationOpened(id: String) = requestDelivery(id)

    fun conversationIdle(id: String) {
        if (!scope.isActive) return
        scope.launch {
            if (jobs.value[id] != null) return@launch
            val child = conversations.getConversation(id) ?: return@launch
            val parent = child.parentConversationId
            if (parent == null) {
                requestDelivery(id)
            } else if (child.subagentResultId != null && child.subagentResultId != child.subagentDeliveredResultId) {
                requestDelivery(parent)
            }
        }
    }

    private suspend fun terminalCompletion(
        id: String, result: TaskExecutionEngine.Result, previousMessageId: String?,
    ): SubagentCompletion? {
        if (conversations.getLiveRun(id) != null) return null
        return when (result) {
            is TaskExecutionEngine.Result.Success ->
                conversations.subagentTerminalCompletion(id, result.modelMessageId)
            is TaskExecutionEngine.Result.Failure -> {
                val messageId = result.modelMessageId ?: registry.get(id)?.streamingMessage?.value?.id
                val terminal = messageId?.takeUnless { it == previousMessageId }
                    ?.let { conversations.subagentTerminalCompletion(id, it) }
                terminal ?: SubagentCompletion(UUID.randomUUID().toString(), "failed", result.reason,
                    errorText = result.reason)
            }
            is TaskExecutionEngine.Result.Busy -> null
        }
    }

    private fun requestDelivery(parentId: String) {
        synchronized(deliveryMonitor) {
            if (!scope.isActive) return
            dirtyParents += parentId
            if (parentId in deliveries) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (synchronized(deliveryMonitor) { dirtyParents.remove(parentId) }) {
                        deliver(parentId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DebugLog.w("SubagentManager", "Result delivery deferred for $parentId", error)
                }
            }
            deliveries[parentId] = job
            job.invokeOnCompletion {
                synchronized(deliveryMonitor) {
                    deliveries.remove(parentId, job)
                    if (scope.isActive && parentId in dirtyParents) requestDelivery(parentId)
                }
            }
            job.start()
        }
    }

    private suspend fun deliver(parentId: String) {
        // Queue admission is legal while the parent is busy. The ordinary drain owns availability,
        // recovery, fresh Run admission and every execution guard; there is no direct fallback.
        enqueueCompletions(parentId)
    }

    private suspend fun authorized(ctx: GenerationContext): RunEffectIdentity {
        check(ctx.subagentToolsEnabled) { "Subagent tools are unavailable in this context" }
        val identity = checkNotNull(ctx.toolEffectIdentity) { "An active tool effect is required" }
        check(identity.conversationId == ctx.conversationId)
        conversations.requireSubagentToolOwner(identity)
        return identity
    }

    private suspend fun ownedChild(identity: RunEffectIdentity, id: String): ChatEntity =
        checkNotNull(conversations.getConversation(id)?.takeIf { it.parentConversationId == identity.conversationId }) {
            "Subagent does not belong to the current conversation"
        }
}
