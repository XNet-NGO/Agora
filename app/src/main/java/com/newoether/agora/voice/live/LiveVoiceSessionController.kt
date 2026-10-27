package com.newoether.agora.voice.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Observable realtime-voice session state. */
enum class LiveVoiceState { IDLE, CONNECTING, ACTIVE }

/** A turn event surfaced to observers (UI) during a live session. */
sealed interface LiveVoiceTurn {
    data class UserTranscript(val text: String) : LiveVoiceTurn
    data class AssistantTranscript(val text: String) : LiveVoiceTurn
    data class Error(val message: String) : LiveVoiceTurn
}

/**
 * Self-contained owner of one Live API voice session (direct Google route). Manual-DI friendly:
 * construct once, call [start] with a resolved [LiveVoiceConfig] + API key, [stop] to end.
 *
 * Minimal first version: audio in/out + transcripts, no tool calling and no DB persistence (a live
 * voice conversation is ephemeral). Playback is driven inside [LiveVoiceStreamer] via the audio
 * manager; this controller just manages lifecycle and surfaces state/turns.
 *
 * Ported/adapted from AIOPE's VoiceSessionController, dropping its Hilt + DB + gateway coupling.
 */
class LiveVoiceSessionController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // readTimeout 0 = no timeout: the WS stays open for the whole conversation.
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var streamer: LiveVoiceStreamer? = null
    private var audio: LiveVoiceAudioManager? = null
    private var collectJob: Job? = null

    private val _state = MutableStateFlow(LiveVoiceState.IDLE)
    val state: StateFlow<LiveVoiceState> = _state.asStateFlow()

    val turns = MutableSharedFlow<LiveVoiceTurn>(replay = 0, extraBufferCapacity = 64)

    val isActive: Boolean get() = _state.value != LiveVoiceState.IDLE

    /**
     * Start a session. [apiKey] is the resolved key for the config's provider. No-op if a session
     * is already running or the config is incomplete.
     */
    @Synchronized
    fun start(config: LiveVoiceConfig, apiKey: String) {
        if (_state.value != LiveVoiceState.IDLE) return
        if (apiKey.isBlank() || config.model.isBlank()) {
            scope.launch { turns.emit(LiveVoiceTurn.Error("Live Voice is not fully configured.")) }
            return
        }
        _state.value = LiveVoiceState.CONNECTING
        val am = LiveVoiceAudioManager().also { audio = it; it.startPlayback() }
        val s = LiveVoiceStreamer(
            okHttp = okHttp,
            apiKey = apiKey,
            modelId = config.model,
            audioManager = am,
            systemInstruction = config.systemInstruction,
            voiceName = config.voiceName.ifBlank { LiveVoiceConfig.DEFAULT_VOICE },
        ).also { streamer = it }

        collectJob = scope.launch {
            s.createStream().collect { event ->
                when (event) {
                    is LiveStreamEvent.Connected -> _state.value = LiveVoiceState.ACTIVE
                    is LiveStreamEvent.Disconnected -> stop()
                    is LiveStreamEvent.Error -> {
                        turns.emit(LiveVoiceTurn.Error(event.message)); stop()
                    }
                    is LiveStreamEvent.InputTranscription -> turns.emit(LiveVoiceTurn.UserTranscript(event.text))
                    is LiveStreamEvent.OutputTranscription -> turns.emit(LiveVoiceTurn.AssistantTranscript(event.text))
                    else -> Unit // AudioChunk handled by the audio manager; TurnComplete/Interrupted no-op here
                }
            }
        }
    }

    /** Send a typed message into the active session (optional text input alongside voice). */
    fun sendText(text: String) {
        streamer?.sendText(text)
        streamer?.endTurn()
    }

    @Synchronized
    fun stop() {
        collectJob?.cancel(); collectJob = null
        streamer?.stop(); streamer = null
        audio = null
        _state.value = LiveVoiceState.IDLE
    }
}
