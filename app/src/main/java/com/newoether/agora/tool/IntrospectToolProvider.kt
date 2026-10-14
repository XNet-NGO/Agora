package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Self-knowledge tool, ported from AIOPE's `introspect`. Answers questions about Agora itself
 * from a bundled manual asset, so the agent can describe its own features from documented
 * behavior rather than guessing.
 *
 * The manual is a single Markdown file under `assets/manual/`. A simple keyword scorer selects
 * the most relevant section; no embedding model is required, keeping the tool dependency-free.
 */
class IntrospectToolProvider(private val context: Context) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.introspectEnabled) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "introspect",
                    description = "Answer questions about Agora itself (features, settings, " +
                        "providers, tools, privacy). Searches a bundled manual and returns the " +
                        "relevant documentation. Use this when the user asks what Agora can do " +
                        "or how to change something.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "query" to ToolProperty(
                                "string",
                                "The question about Agora to look up.",
                            ),
                        ),
                        required = listOf("query"),
                    ),
                ),
            ),
        )
    }

    override fun handles(name: String): Boolean = name == "introspect"

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String {
        if (!ctx.introspectEnabled) return error("Introspect is disabled")
        val args = runCatching {
            Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
        }.getOrNull() ?: return error("Arguments are not a JSON object.")
        val query = (args["query"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return error("query is required")
        val manual = loadManual()
        if (manual.isBlank()) return error("Manual not found")
        val section = bestSection(manual, query)
        return buildJsonObject {
            put("type", "introspect")
            put("query", query)
            put("manual", section)
        }.toString()
    }

    private suspend fun loadManual(): String = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open("manual/agora-manual.md").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    /**
     * Split the manual into `##` sections and score each by keyword overlap with the query.
     * Returns the best-matching section (or the whole manual if no section header exists).
     */
    private fun bestSection(manual: String, query: String): String {
        val sections = manual.split(Regex("(?m)^## "))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (sections.isEmpty()) return manual
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return sections.first()
        val scored = sections.map { section ->
            val sectionTokens = tokenize(section)
            val score = queryTokens.count { it in sectionTokens }
            section to score
        }
        return scored.maxByOrNull { it.second }?.first ?: sections.first()
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .toSet()

    private fun error(message: String): String = "Error: $message"
}