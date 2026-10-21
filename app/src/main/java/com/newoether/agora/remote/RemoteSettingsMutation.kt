package com.newoether.agora.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Owns one active native write and one merged, undispatched patch per conversation. */
internal class RemoteSettingsMutation(
    private val scope: CoroutineScope,
    private val mutableState: MutableStateFlow<RemoteState>,
    private val clientForDevice: (String?) -> FiloClient?,
    private val selectionEpoch: () -> Long,
    private val readEpoch: () -> Long,
    private val acceptPage: suspend (FiloClient, String, Long, RemoteConversationPage) -> Unit,
    private val readsSettled: () -> Unit,
    private val report: (Exception) -> RemoteFailure?,
) {
    private data class Patch(val settings: RemoteSettings, val selection: Long)
    private class Owner(
        val id: String,
        val device: String,
        val session: String,
        val client: FiloClient,
        var pending: Patch?,
        var active: Patch? = null,
        var confirmed: RemoteRuntime? = null,
    )
    private val owners = mutableMapOf<String, Owner>()
    private var settingsVersion = 0L
    fun snapshotVersion(): Long = settingsVersion
    fun acceptsSnapshot(version: Long, ownerId: String): Boolean =
        version == settingsVersion && owners[ownerId]?.active == null
    fun confirmedRuntime(ownerId: String): RemoteRuntime? = owners[ownerId]?.takeIf {
        clientForDevice(it.device) === it.client
    }?.confirmed

    fun setThinkingEnabled(enabled: Boolean) {
        val snapshot = mutableState.value
        if (enabled == (snapshot.selectedEffort != null && snapshot.selectedEffort != "none")) return
        val model = snapshot.settingsModel ?: return
        val effort = if (enabled) model.defaultReasoningEffort?.takeUnless { it == "none" }
            ?: model.reasoningEfforts?.firstOrNull { it != "none" } else "none"
        effort?.let(::setThinkingLevel)
    }

    fun setThinkingLevel(effort: String) {
        val snapshot = mutableState.value
        if (effort == snapshot.selectedEffort || effort !in snapshot.settingsModel?.reasoningEfforts.orEmpty()) return
        change(RemoteSettings(effort = effort))
    }

    fun setServiceTierEnabled(enabled: Boolean) {
        val snapshot = mutableState.value
        if (enabled == (snapshot.selectedServiceTier != null)) return
        val model = snapshot.settingsModel ?: return
        val tier = if (enabled) model.defaultServiceTier?.takeIf { value -> model.serviceTiers.orEmpty().any { it.id == value } }
            ?: model.serviceTiers?.firstOrNull()?.id ?: return else null
        setServiceTier(tier)
    }

    fun setServiceTier(tier: String?) {
        val snapshot = mutableState.value
        if (snapshot.settingsModel?.serviceTiers == null || (tier == snapshot.selectedServiceTier && snapshot.serviceTierKnown) ||
            tier != null && snapshot.settingsModel?.serviceTiers.orEmpty().none { it.id == tier }) return
        change(RemoteSettings(serviceTier = tier, updateServiceTier = true))
    }

    private fun change(settings: RemoteSettings) {
        val snapshot = mutableState.value
        if (!snapshot.canEditSettings) return
        if (snapshot.isDraft) {
            mutableState.value = snapshot.copy(draftSettings = snapshot.draftSettings.merge(settings))
            return
        }
        val ownerId = snapshot.owner ?: return
        val device = snapshot.deviceId ?: return
        val session = snapshot.session ?: return
        val client = clientForDevice(device) ?: return
        val existing = owners[ownerId]
        // A replaced connection cannot inherit a write admitted with different credentials.
        if (existing != null && existing.client !== client) return
        val patch = Patch(settings, selectionEpoch())
        if (existing != null) {
            existing.pending = patch.copy(settings = (existing.pending?.settings ?: RemoteSettings()).merge(settings))
            publish(existing)
            return
        }
        val owner = Owner(ownerId, device, session.id, client, patch, confirmed = snapshot.runtime)
        owners[ownerId] = owner
        publish(owner)
        scope.launch { drain(owner) }
    }

    /** Model changes retain the ordinary control barrier rather than becoming optimistic. */
    suspend fun changeModel(client: FiloClient, session: String, settings: RemoteSettings) {
        val snapshot = mutableState.value
        val ownerId = snapshot.owner ?: return
        val device = snapshot.deviceId ?: return
        val owner = Owner(ownerId, device, session, client, null)
        writeAndRead(owner, Patch(settings, selectionEpoch()))
    }

    private suspend fun drain(owner: Owner) {
        var succeeded = false
        try {
            while (clientForDevice(owner.device) === owner.client) {
                val patch = owner.pending ?: break
                owner.pending = null
                owner.active = patch
                publish(owner)
                try {
                    succeeded = false
                    writeAndRead(owner, patch)
                    succeeded = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (isSelected(owner)) {
                        val failure = report(error)
                        mutableState.value = mutableState.value.copy(failure = failure)
                    }
                } finally {
                    // The pending patch may contain a newer choice for either field.
                    owner.active = null
                    publish(owner)
                    if (isSelected(owner)) {
                        mutableState.value = mutableState.value.copy(
                            settingsRevision = mutableState.value.settingsRevision + 1,
                        )
                    }
                }
            }
        } finally {
            owner.active = null
            owner.pending = null
            publish(owner)
            owners.remove(owner.id)
            // Fence already-buffered stream pages before releasing the final presentation.
            if (succeeded && isSelected(owner)) readsSettled()
        }
    }

    private suspend fun writeAndRead(owner: Owner, patch: Patch) {
        settingsVersion++
        try {
            owner.client.updateSettings(owner.session, patch.settings)
            val generation = readEpoch()
            val page = owner.client.conversation(owner.session)
            owner.confirmed = page.runtime
            if (isCurrent(owner, patch.selection)) acceptPage(owner.client, owner.session, generation, page)
            check(confirms(page.runtime, patch.settings)) { "Native settings were not confirmed" }
        } finally {
            settingsVersion++
        }
    }

    private fun isCurrent(owner: Owner, selection: Long): Boolean =
        selection == selectionEpoch() && isSelected(owner)

    private fun isSelected(owner: Owner): Boolean {
        val snapshot = mutableState.value
        return snapshot.owner == owner.id && snapshot.deviceId == owner.device &&
            snapshot.session?.id == owner.session && clientForDevice(owner.device) === owner.client
    }

    private fun publish(owner: Owner) {
        val snapshot = mutableState.value
        val active = owner.active?.settings
        val pending = owner.pending?.settings
        val projection = if (clientForDevice(owner.device) !== owner.client) null else when {
            active != null && pending != null -> active.merge(pending)
            else -> pending ?: active
        }
        mutableState.value = snapshot.copy(
            optimisticSettings = if (projection == null) snapshot.optimisticSettings - owner.id
            else snapshot.optimisticSettings + (owner.id to projection),
        )
    }

    private fun confirms(runtime: RemoteRuntime?, settings: RemoteSettings): Boolean {
        runtime ?: return false
        return (settings.model == null || settings.model == runtime.model) &&
            (settings.effort == null || settings.effort == runtime.effort) &&
            (!settings.updateServiceTier ||
                (runtime.serviceTierKnown || runtime.serviceTier != null) &&
                settings.serviceTier.takeUnless { it == "default" } == runtime.serviceTier.takeUnless { it == "default" })
    }
}
