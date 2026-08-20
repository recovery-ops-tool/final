package expo.modules.callrecording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Foreground service whose only job is to keep this app's process alive (and therefore its JS
 * runtime, and the expo-audio recorder already running there) while the agent backgrounds the app
 * to use the native Phone dialer. It does not record audio itself -- the JS side handles that via
 * expo-audio, the same mechanism already used for the SOS panic-button recording. Android requires
 * a foreground service (with foregroundServiceType="microphone") for a mic-using process to keep
 * running in the background at all; without this, the recording would very likely be suspended
 * the moment the native dialer takes over.
 *
 * The persistent notification exposes Pause/Resume/Stop actions, and (when SYSTEM_ALERT_WINDOW is
 * granted) a draggable overlay "quick ball" -- see [CallBubbleController] -- shown while the app is
 * backgrounded. Both feed into the same [handleControlAction], which relays to [CallRecordingModule]
 * through the static [listener], since only the JS side can actually control the expo-audio recorder.
 */
class CallRecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "call_recording_channel"
        const val NOTIFICATION_ID = 8821

        const val ACTION_PAUSE = "expo.modules.callrecording.action.PAUSE"
        const val ACTION_RESUME = "expo.modules.callrecording.action.RESUME"
        const val ACTION_STOP = "expo.modules.callrecording.action.STOP"
        const val ACTION_SHOW_BUBBLE = "expo.modules.callrecording.action.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "expo.modules.callrecording.action.HIDE_BUBBLE"

        /** Set by CallRecordingModule while it's alive, so notification/bubble taps can reach JS. */
        var listener: ((String) -> Unit)? = null
    }

    private var isPaused = false
    private var bubble: CallBubbleController? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> handleControlAction(ACTION_PAUSE)
            ACTION_RESUME -> handleControlAction(ACTION_RESUME)
            ACTION_STOP -> handleControlAction(ACTION_STOP)
            ACTION_SHOW_BUBBLE -> showBubble()
            ACTION_HIDE_BUBBLE -> hideBubble()
            else -> {
                isPaused = false
                startForegroundWithNotification()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideBubble()
        super.onDestroy()
    }

    private fun handleControlAction(action: String) {
        when (action) {
            ACTION_PAUSE -> isPaused = true
            ACTION_RESUME -> isPaused = false
        }
        listener?.invoke(action)
        if (action == ACTION_STOP) {
            hideBubble()
            stopSelf()
            return
        }
        updateNotification()
        bubble?.setPaused(isPaused)
    }

    private fun showBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        val controller = bubble ?: CallBubbleController(this).also {
            it.onPauseResumeTapped = { handleControlAction(if (isPaused) ACTION_RESUME else ACTION_PAUSE) }
            it.onStopTapped = { handleControlAction(ACTION_STOP) }
            bubble = it
        }
        controller.show(isPaused)
    }

    private fun hideBubble() {
        bubble?.hide()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call recording",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val pauseResumeAction = if (isPaused) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Resume",
                controlPendingIntent(ACTION_RESUME)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                controlPendingIntent(ACTION_PAUSE)
            )
        }

        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            controlPendingIntent(ACTION_STOP)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording call for compliance")
            .setContentText(if (isPaused) "Recording paused" else "Recording in progress")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(pauseResumeAction)
            .addAction(stopAction)
            .build()
    }

    private fun controlPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, CallRecordingService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, action.hashCode(), intent, flags)
        } else {
            PendingIntent.getService(this, action.hashCode(), intent, flags)
        }
    }
}
