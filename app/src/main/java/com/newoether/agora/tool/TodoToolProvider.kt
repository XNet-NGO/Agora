package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.TodoItem
import com.newoether.agora.data.TodoStore
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Agent-managed working todo list, ported from AIOPE's `todo_write` / `todo_read` tools.
 *
 * The model writes a plan before a multi-step job and updates it as it works, so long tasks stay
 * on track across many tool calls. This is a lightweight scratch list distinct from Agora's
 * durable Room-backed Tasks/Loops automation.
 */
class TodoToolProvider(private val store: TodoStore) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.todoEnabled) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "todo_write",
                    description = "Write, replace, or upsert the persistent working todo list. " +
                        "Each item has an id, title, status (pending/in_progress/done), and optional " +
                        "notes. Set merge=true to upsert by id (preserving createdAt); otherwise the " +
                        "whole list is replaced. Use this before a multi-step job and update it as you work.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "items" to ToolProperty(
                                type = "array",
                                description = "The todo items to write.",
                                items = ToolProperty(
                                    type = "object",
                                    description = "One todo item.",
                                    properties = mapOf(
                                        "id" to ToolProperty("string", "Stable item id."),
                                        "title" to ToolProperty("string", "Short task title."),
                                        "status" to ToolProperty(
                                            "string",
                                            "pending, in_progress, or done.",
                                        ),
                                        "notes" to ToolProperty("string", "Optional notes."),
                                    ),
                                    required = listOf("id", "title"),
                                ),
                            ),
                            "merge" to ToolProperty(
                                "boolean",
                                "True upserts by id; false replaces the whole list. Default false.",
                            ),
                        ),
                        required = listOf("items"),
                    ),
                ),
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "todo_read",
                    description = "Read the working todo list grouped by status with counts.",
                    parameters = ToolParameters(properties = emptyMap()),
                ),
            ),
        )
    }

    override fun handles(name: String): Boolean = name in TOOL_NAMES

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String {
        if (!ctx.todoEnabled) return error("Todo tools are disabled")
        return when (name) {
            "todo_write" -> executeWrite(arguments)
            "todo_read" -> executeRead()
            else -> error("Unknown todo tool: $name")
        }
    }

    private suspend fun executeWrite(arguments: String): String {
        val args = runCatching {
            Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
        }.getOrNull() ?: return error("Arguments are not a JSON object.")
        val merge = (args["merge"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        val itemsJson = args["items"] as? kotlinx.serialization.json.JsonArray
            ?: return error("items is required")
        val items = itemsJson.mapNotNull { element ->
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = (obj["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            TodoItem(
                id = id,
                title = title,
                status = (obj["status"] as? JsonPrimitive)?.content ?: "pending",
                notes = (obj["notes"] as? JsonPrimitive)?.content,
            )
        }
        if (items.isEmpty()) return error("items must contain at least one valid item")
        val now = System.currentTimeMillis()
        val written = items.map { item ->
            store.upsert(item.copy(updatedAt = now), merge)
        }
        return buildJsonObject {
            put("type", "todo_write")
            put("count", written.size)
            put("merge", merge)
        }.toString()
    }

    private suspend fun executeRead(): String {
        val list = store.read()
        val grouped = list.items.groupBy { it.status }
        return buildJsonObject {
            put("type", "todo_read")
            put("total", list.items.size)
            putJsonArray("items") {
                list.items.forEach { item ->
                    add(
                        buildJsonObject {
                            put("id", item.id)
                            put("title", item.title)
                            put("status", item.status)
                            item.notes?.let { put("notes", it) }
                        },
                    )
                }
            }
            putJsonArray("groups") {
                listOf("pending", "in_progress", "done").forEach { status ->
                    add(
                        buildJsonObject {
                            put("status", status)
                            put("count", grouped[status].orEmpty().size)
                        },
                    )
                }
            }
        }.toString()
    }

    private fun error(message: String): String = "Error: $message"

    private companion object {
        const val TODO_WRITE = "todo_write"
        const val TODO_READ = "todo_read"
        val TOOL_NAMES = setOf(TODO_WRITE, TODO_READ)
    }
}