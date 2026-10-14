package com.newoether.agora.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent working todo list for the agent, ported from AIOPE's `todo_write` / `todo_read`
 * tools. Unlike the durable Room-backed Tasks/Loops automation, this is a lightweight
 * agent-managed scratch list the model writes before a multi-step job and updates as it works.
 *
 * Storage is a single JSON file in the app's private files dir. It is intentionally simple and
 * synchronous-friendly: the list is small, and the tool provider reads/writes it on Dispatchers.IO.
 */
@Serializable
data class TodoItem(
    val id: String,
    val title: String,
    val status: String = "pending", // pending | in_progress | done
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class TodoList(
    val items: List<TodoItem> = emptyList(),
)

/**
 * File-backed store for the agent todo list. A single writer is assumed per process (the tool
 * executor serializes tool execution), so no locking is required beyond the IO dispatcher.
 */
class TodoStore(private val context: Context) {

    private val file: File
        get() = File(context.filesDir, "agent_todo.json")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun read(): TodoList = withContext(Dispatchers.IO) {
        val f = file
        if (!f.exists()) return@withContext TodoList()
        runCatching { json.decodeFromString<TodoList>(f.readText()) }
            .getOrDefault(TodoList())
    }

    suspend fun write(list: TodoList) = withContext(Dispatchers.IO) {
        file.writeText(json.encodeToString(list))
    }

    /**
     * Upsert an item by id. When [merge] is true and an item with the same id exists, its
     * mutable fields (title/status/notes) are replaced while createdAt is preserved.
     */
    suspend fun upsert(item: TodoItem, merge: Boolean): TodoItem = withContext(Dispatchers.IO) {
        val current = read()
        val existing = current.items.firstOrNull { it.id == item.id }
        val merged = if (merge && existing != null) {
            item.copy(createdAt = existing.createdAt)
        } else {
            item
        }
        val updated = if (existing != null) {
            current.items.map { if (it.id == item.id) merged else it }
        } else {
            current.items + merged
        }
        write(TodoList(updated))
        merged
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        write(TodoList())
    }
}