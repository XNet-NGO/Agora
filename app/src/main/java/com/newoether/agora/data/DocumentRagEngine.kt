package com.newoether.agora.data

import com.newoether.agora.api.EmbeddingClient
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.data.local.DocumentEmbeddingDao
import com.newoether.agora.data.local.DocumentEmbeddingEntity
import com.newoether.agora.data.local.EmbeddingSearchRow
import com.newoether.agora.tool.BoundedSemanticEmbeddingSelector
import com.newoether.agora.util.DebugLog
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One corpus document to index: a stable id, a title, and the full markdown/plain body. */
data class CorpusDocument(val docId: String, val title: String, val body: String)

/** A semantic hit: the full document plus its best chunk score. */
data class DocumentRagHit(val docId: String, val title: String, val body: String, val score: Float)

/**
 * Generic semantic store for a document corpus (e.g. the bundled manual), isolated from the
 * conversation message corpus. Reuses the exact embedding generation ([EmbeddingClient] /
 * [LlamaEngine]), byte encoding ([EmbeddingIndexer]) and bounded ranking
 * ([BoundedSemanticEmbeddingSelector]) that conversation RAG uses — only the storage table differs.
 *
 * All methods are no-ops / empty when no embedding model is configured; the caller supplies a
 * keyword fallback for that case.
 */
class DocumentRagEngine(
    private val dao: DocumentEmbeddingDao,
    private val corpus: String,
) {
    private val selector = BoundedSemanticEmbeddingSelector()

    /** True if this corpus already has vectors for [modelId] matching the given fingerprint set. */
    suspend fun isIndexed(modelId: String, fingerprint: String): Boolean =
        withContext(Dispatchers.IO) {
            val fps = dao.fingerprintsFor(corpus, modelId)
            dao.countFor(corpus, modelId) > 0 && fps.isNotEmpty() && fps.all { it == fingerprint }
        }

    /**
     * Re-indexes [documents] for [config]. Clears the corpus first so renamed/removed documents and
     * stale model dimensions never linger. Returns the number of chunks stored (0 on failure/no model).
     */
    suspend fun reindex(
        documents: List<CorpusDocument>,
        config: EmbeddingModelConfig,
        apiKey: String?,
    ): Int = withContext(Dispatchers.IO) {
        val fingerprint = corpusFingerprint(documents)
        val rows = mutableListOf<DocumentEmbeddingEntity>()
        for (doc in documents) {
            val chunks = chunk(doc.body)
            chunks.forEachIndexed { index, chunkText ->
                val vector = embed(chunkText, config, apiKey) ?: return@withContext 0
                rows += DocumentEmbeddingEntity(
                    corpus = corpus,
                    docId = doc.docId,
                    title = doc.title,
                    chunkIndex = index,
                    modelId = config.id,
                    embedding = EmbeddingIndexer.floatsToBytes(vector),
                    dimension = vector.size,
                    chunkText = chunkText,
                    sourceFingerprint = fingerprint,
                )
            }
        }
        if (rows.isEmpty()) return@withContext 0
        dao.clearCorpus(corpus)
        rows.chunked(INSERT_BATCH).forEach { dao.insertAll(it) }
        DebugLog.d(TAG, "indexed ${documents.size} docs -> ${rows.size} chunks for corpus=$corpus")
        rows.size
    }

    /**
     * Semantically ranks [documents] for [query], returning the best-matching documents (deduped to
     * one hit per document, by its top chunk score). Empty if no model or no query embedding.
     */
    suspend fun search(
        query: String,
        limit: Int,
        threshold: Float,
        config: EmbeddingModelConfig,
        apiKey: String?,
        documents: List<CorpusDocument>,
    ): List<DocumentRagHit> = withContext(Dispatchers.IO) {
        val queryVector = embed(query, config, apiKey) ?: return@withContext emptyList()
        // Over-fetch chunk candidates, then collapse to the best chunk per document.
        val selection = selector.select(
            queryEmbedding = queryVector,
            threshold = threshold,
            limit = limit * CHUNK_OVERFETCH,
        ) { afterId, pageLimit ->
            dao.pageFor(corpus, config.id, afterId, pageLimit).map { row ->
                EmbeddingSearchRow(
                    id = row.id,
                    messageId = row.docId, // selector's "messageId" carries our docId here
                    embedding = row.embedding,
                    dimension = row.dimension,
                )
            }
        }
        val byDoc = LinkedHashMap<String, Float>()
        for (candidate in selection.candidates) {
            val existing = byDoc[candidate.messageId]
            if (existing == null || candidate.score > existing) {
                byDoc[candidate.messageId] = candidate.score
            }
        }
        val titles = documents.associate { it.docId to (it.title to it.body) }
        byDoc.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { (docId, score) ->
                val doc = titles[docId] ?: return@mapNotNull null
                DocumentRagHit(docId = docId, title = doc.first, body = doc.second, score = score)
            }
    }

    private suspend fun embed(
        text: String,
        config: EmbeddingModelConfig,
        apiKey: String?,
    ): FloatArray? {
        return if (config.type == EmbeddingModelType.LOCAL) {
            if (!LlamaEngine.isModelReady(config.localFilePath)) return null
            LlamaEngine.computeEmbedding(text, config.localFilePath)
        } else {
            if (apiKey.isNullOrBlank()) return null
            EmbeddingClient.computeEmbedding(
                text = text,
                apiKey = apiKey,
                model = config.remoteModelName,
                baseUrl = config.remoteBaseUrl.ifBlank { ProviderDefaults.OPENAI_BASE_URL },
            )
        }
    }

    companion object {
        private const val TAG = "DocumentRagEngine"
        private const val INSERT_BATCH = 64
        private const val CHUNK_OVERFETCH = 3

        /** Sentence-aware chunking with a soft character budget, mirroring RAG's approach. */
        fun chunk(body: String, maxChars: Int = 1200): List<String> {
            val normalized = body.trim()
            if (normalized.isEmpty()) return emptyList()
            if (normalized.length <= maxChars) return listOf(normalized)
            val sentences = normalized.split(Regex("(?<=[.!?])\\s+|\\n{2,}"))
            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            for (sentence in sentences) {
                if (current.isNotEmpty() && current.length + sentence.length + 1 > maxChars) {
                    chunks += current.toString().trim()
                    current.setLength(0)
                }
                if (sentence.length > maxChars) {
                    // A single oversized sentence: hard-split on the budget.
                    sentence.chunked(maxChars).forEach { chunks += it }
                } else {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(sentence)
                }
            }
            if (current.isNotEmpty()) chunks += current.toString().trim()
            return chunks.filter { it.isNotBlank() }
        }

        /** Stable fingerprint over the whole corpus; changes when any doc text/title/id changes. */
        fun corpusFingerprint(documents: List<CorpusDocument>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            documents.sortedBy { it.docId }.forEach { doc ->
                digest.update(doc.docId.toByteArray())
                digest.update(doc.title.toByteArray())
                digest.update(doc.body.toByteArray())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
        }
    }
}
