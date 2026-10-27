package com.newoether.agora.voice.live

import kotlinx.serialization.Serializable

/**
 * User-selected Live Voice (realtime bidirectional voice) configuration, persisted in settings.
 *
 * [provider] is the provider display name (initially only Google is supported by the direct
 * BidiGenerateContent route); [model] is a Live-capable model id selected from that provider's
 * model list; [voiceName] is a Google prebuilt voice (Aoede, Puck, Charon, Kore, Fenrir, ...).
 */
@Serializable
data class LiveVoiceConfig(
    val enabled: Boolean = false,
    val provider: String = "",
    val model: String = "",
    val voiceName: String = DEFAULT_VOICE,
    val systemInstruction: String = "",
) {
    companion object {
        const val DEFAULT_VOICE = "Aoede"

        /** Google prebuilt Live API voices. */
        val GOOGLE_VOICES = listOf("Aoede", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Zephyr")

        /**
         * Heuristic for Live-capable model ids so the model picker can filter. Google's Live API
         * models carry "live" in the id (e.g. gemini-2.0-flash-live-001, gemini-2.5-flash-...-live).
         */
        fun looksLiveCapable(modelId: String): Boolean {
            val id = modelId.lowercase()
            return id.contains("live") || id.contains("realtime") || id.contains("native-audio")
        }
    }
}
