package com.newoether.agora.voice.live

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask

/**
 * Bidirectional realtime voice over Google's Live API (direct BidiGenerateContent).
 *
 * Ported from AIOPE (feature-chat/engine/RealtimeStreaming.kt), reduced to the DIRECT Google route
 * only — the AIOPE gateway protocol/path was intentionally dropped. Audio-only responses; emits
 * [LiveStreamEvent]s. Capture starts after the server acknowledges setup.
 *
 * Endpoint: wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=API_KEY
 */
class LiveVoiceStreamer(
    private val okHttp: OkHttpClient,
    private val apiKey: String,
    private val modelId: String,
    private val audioManager: LiveVoiceAudioManager,
    private val systemInstruction: String = "",
    private val voiceName: String = LiveVoiceConfig.DEFAULT_VOICE,
) {
    private var webSocket: WebSocket? = null

    /** Normalize a possibly-prefixed id to Google's "models/<name>" form. */
    private fun googleModelId(): String {
        val stripped = if (modelId.contains("/") && !modelId.startsWith("models/") && !modelId.startsWith("@")) {
            modelId.substringAfter("/")
        } else {
            modelId
        }
        return if (stripped.startsWith("models/")) stripped else "models/$stripped"
    }

    fun createStream(): Flow<LiveStreamEvent> = callbackFlow {
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = okHttp.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket opened: ${response.code} model=$modelId")
                    sendSetup(ws)
                    audioManager.setWebSocket(ws)
                    // Capture starts only after setupComplete.
                }

                override fun onMessage(ws: WebSocket, text: String) = handleIncoming(text)

                override fun onMessage(ws: WebSocket, bytes: ByteString) = handleIncoming(bytes.utf8())

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    trySend(LiveStreamEvent.Error(t.message ?: "Connection failed")); close()
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    trySend(LiveStreamEvent.Disconnected); close()
                }

                private fun handleIncoming(text: String) {
                    try {
                        val json = JSONObject(text)
                        if (json.has("setupComplete")) {
                            Log.i(TAG, "setupComplete — starting capture")
                            audioManager.startCapture()
                            trySend(LiveStreamEvent.Connected)
                            return
                        }
                        if (json.has("goAway")) {
                            Log.w(TAG, "GoAway: ${json.optJSONObject("goAway")?.optString("timeLeft")}")
                            return
                        }
                        parseServerMessages(text).forEach { trySend(it) }
                    } catch (e: Exception) {
                        Log.e(TAG, "parse error: ${e.message}")
                    }
                }
            },
        )

        // Heartbeat to keep the connection from idling out.
        val heartbeat = Timer("live-voice-heartbeat", true)
        heartbeat.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() { runCatching { webSocket?.send("") } }
            },
            15000L, 15000L,
        )

        awaitClose { heartbeat.cancel(); stop() }
    }.flowOn(Dispatchers.IO)

    private fun sendSetup(ws: WebSocket) {
        val setup = JSONObject().apply {
            put(
                "setup",
                JSONObject().apply {
                    put("model", googleModelId())
                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("responseModalities", JSONArray().put("AUDIO"))
                            put(
                                "speechConfig",
                                JSONObject().apply {
                                    put(
                                        "voiceConfig",
                                        JSONObject().apply {
                                            put(
                                                "prebuiltVoiceConfig",
                                                JSONObject().apply { put("voiceName", voiceName) },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                    if (systemInstruction.isNotBlank()) {
                        put(
                            "systemInstruction",
                            JSONObject().apply {
                                put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                            },
                        )
                    }
                    put("outputAudioTranscription", JSONObject())
                    put("inputAudioTranscription", JSONObject())
                    put("sessionResumption", JSONObject())
                    put(
                        "contextWindowCompression",
                        JSONObject().apply {
                            put("slidingWindow", JSONObject().apply { put("targetTokens", 8000) })
                            put("triggerTokens", 25000)
                        },
                    )
                    put(
                        "realtimeInputConfig",
                        JSONObject().apply { put("turnCoverage", "TURN_INCLUDES_ALL_INPUT") },
                    )
                },
            )
        }
        ws.send(setup.toString())
    }

    private fun parseServerMessages(text: String): List<LiveStreamEvent> {
        val events = mutableListOf<LiveStreamEvent>()
        val json = JSONObject(text)
        val serverContent = json.optJSONObject("serverContent")
        if (serverContent != null) {
            serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    part.optJSONObject("inlineData")?.let { inline ->
                        if (inline.optString("mimeType", "").startsWith("audio/")) {
                            val b64 = inline.optString("data", "")
                            if (b64.isNotBlank()) {
                                val pcm = Base64.decode(b64, Base64.DEFAULT)
                                audioManager.playAudio(pcm)
                                events.add(LiveStreamEvent.AudioChunk(pcm))
                            }
                        }
                    }
                    part.optString("text", "").let { if (it.isNotBlank()) events.add(LiveStreamEvent.TextDelta(it)) }
                }
            }
            if (serverContent.optBoolean("turnComplete", false)) events.add(LiveStreamEvent.TurnComplete)
            if (serverContent.optBoolean("interrupted", false)) {
                audioManager.clearPlayback()
                events.add(LiveStreamEvent.Interrupted)
            }
            serverContent.optJSONObject("inputTranscription")?.optString("text")?.let {
                if (it.isNotBlank()) events.add(LiveStreamEvent.InputTranscription(it))
            }
            serverContent.optJSONObject("outputTranscription")?.optString("text")?.let {
                if (it.isNotBlank()) events.add(LiveStreamEvent.OutputTranscription(it))
            }
        }
        return events
    }

    /** Send a text turn mid-conversation. */
    fun sendText(text: String) {
        webSocket?.send(JSONObject().apply { put("realtimeInput", JSONObject().apply { put("text", text) }) }.toString())
    }

    /** Signal end of the user's turn. */
    fun endTurn() {
        webSocket?.send("""{"clientContent":{"turnComplete":true}}""")
    }

    fun stop() {
        audioManager.stop()
        webSocket?.close(1000, "Client ended")
        webSocket = null
    }

    companion object { private const val TAG = "LiveVoiceStreamer" }
}
