package com.newoether.agora.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.newoether.agora.MainActivity

/**
 * A small draggable floating button drawn over other apps via SYSTEM_ALERT_WINDOW.
 * Tapping it invokes the Agora assistant so the user can reach it while any other
 * app is in the foreground. Purely an overlay UI on Agora's own window — it does
 * not read or inject input into other apps. Real on-screen context capture is
 * done by the assist gesture path (VoiceInteractionSession); this bubble is just
 * a convenient launcher.
 *
 * Only started when the user enables the feature AND has granted the
 * draw-over-other-apps permission.
 *
 * Agora uses manual DI (no Hilt), so tapping the bubble launches an assistant
 * turn through [AssistInvocation] rather than toggling a DI-provided controller.
 */
class AssistantOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        if (canDraw(this)) addBubble() else stopSelf()
    }

    private fun addBubble() {
        if (bubble != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val size = (56 * resources.displayMetrics.density).toInt()
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BUBBLE_COLOR)
        }
        val iv = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = bg
            setPadding(size / 4, size / 4, size / 4, size / 4)
            alpha = 0.9f
        }

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }

        // Drag to move; tap (no significant movement) to invoke the assistant.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        iv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) moved = true
                    params.x = startX + dx; params.y = startY + dy
                    runCatching { wm.updateViewLayout(iv, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) launchAssistant()
                    true
                }

                else -> false
            }
        }

        runCatching { wm.addView(iv, params) }
        bubble = iv
    }

    private fun launchAssistant() {
        // The overlay has no screen capture of its own; invoke a turn with empty
        // screen context. (The assist-gesture path captures the real screen.)
        try {
            AssistInvocation.launch(this, screenContext = "")
        } catch (e: Exception) {
            Log.e(TAG, "overlay assistant invoke failed: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        bubble?.let { b -> runCatching { windowManager?.removeView(b) } }
        bubble = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Agora Assistant Button", NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b
            .setContentTitle("Agora assistant button active")
            .setContentText("Tap the floating button to talk to Agora")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val TAG = "AgoraAssistOverlay"
        private const val CHANNEL_ID = "agora_assistant_overlay"
        private const val NOTIFICATION_ID = 73
        private const val BUBBLE_COLOR = 0xFF1E88E5.toInt()

        fun canDraw(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun start(context: Context) {
            val i = Intent(context, AssistantOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantOverlayService::class.java))
        }
    }
}
