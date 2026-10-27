package com.newoether.agora.voice.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Mic capture → WebSocket (base64 PCM) and playback of PCM chunks for the Live API session.
 * [LiveVoiceStreamer] owns this and sets the WebSocket after connection.
 *
 * Ported from AIOPE (feature-chat/engine/RealtimeAudioManager.kt), trimmed to the Google-direct
 * path: input PCM at 16 kHz is sent as realtimeInput.audio; model audio plays back at 24 kHz.
 * AcousticEchoCanceler + shared audio session give barge-in-friendly echo cancellation.
 */
class LiveVoiceAudioManager(
    private val config: LiveAudioConfig = LiveAudioConfig(),
) {
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var webSocket: WebSocket? = null
    private var aec: AcousticEchoCanceler? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    private var sharedSessionId = 0

    fun setWebSocket(ws: WebSocket) { webSocket = ws }

    /** Clear queued playback on interruption / barge-in. */
    fun clearPlayback() { playbackQueue.clear() }

    fun startCapture() {
        if (audioRecord != null) {
            captureJob?.cancel(); captureJob = null
            audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        }
        val rate = config.inputSampleRate
        val bufSize = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (bufSize <= 0) return
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord init failed", t); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        audioRecord = rec
        sharedSessionId = rec.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sharedSessionId)?.apply { enabled = true }
        }
        // Rebuild playback track to share the session id for AEC pairing.
        if (audioTrack != null && audioTrack?.audioSessionId != sharedSessionId) {
            playbackJob?.cancel(); playbackJob = null
            audioTrack?.stop(); audioTrack?.release(); audioTrack = null
            playbackQueue.clear(); startPlayback()
        }
        rec.startRecording()
        val sampleRate = rate
        captureJob = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(bufSize)
            while (isActive) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: break
                if (read > 0) {
                    val encoded = Base64.encodeToString(buf.copyOf(read), Base64.NO_WRAP)
                    webSocket?.send(
                        """{"realtimeInput":{"audio":{"mimeType":"audio/pcm;rate=$sampleRate","data":"$encoded"}}}""",
                    )
                }
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel(); captureJob = null
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
    }

    fun startPlayback(outputRate: Int = config.outputSampleRate) {
        val bufSize = AudioTrack.getMinBufferSize(
            outputRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(outputRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(bufSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (sharedSessionId != 0) builder.setSessionId(sharedSessionId)
        audioTrack = builder.build().apply { play() }
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val chunk = playbackQueue.poll(100, TimeUnit.MILLISECONDS)
                if (chunk != null) audioTrack?.write(chunk, 0, chunk.size)
            }
        }
    }

    fun playAudio(pcmData: ByteArray) { playbackQueue.offer(pcmData) }

    fun stop() {
        stopCapture()
        playbackJob?.cancel(); playbackJob = null
        playbackQueue.clear()
        aec?.release(); aec = null
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        webSocket = null
    }

    companion object { private const val TAG = "LiveVoiceAudio" }
}
