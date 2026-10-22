package com.newoether.agora.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Minimal [RecognitionService].
 *
 * A VoiceInteractionService must reference a recognition service in its
 * voice_interaction_service.xml. Agora's Layer 1 assistant is invoked by the
 * assist gesture / power button (not voice), so this is an intentional stub
 * that reports "not available" rather than performing speech recognition.
 * Offline STT (sherpa-onnx, as in AIOPE) can replace this later to add voice.
 */
class AgoraRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        } catch (_: Exception) {
        }
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
