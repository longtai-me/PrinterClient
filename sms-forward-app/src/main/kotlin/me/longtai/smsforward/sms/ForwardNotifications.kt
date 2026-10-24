package me.longtai.smsforward.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import me.longtai.smsforward.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a persistent, ongoing notification visible the entire time forwarding is enabled, so that
 * anyone holding the device can see their SMS are being forwarded and to which number. This is a
 * deliberate transparency guarantee — the feature is never silent.
 */
@Singleton
class ForwardNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "轉發狀態", NotificationManager.IMPORTANCE_LOW).apply {
                description = "顯示簡訊轉發是否正在運作"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENT, "轉發事件", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "每次轉發簡訊時的通知"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COMMAND, "Telegram 指令", NotificationManager.IMPORTANCE_LOW).apply {
                description = "顯示是否正在接收 Telegram 指令"
            },
        )
    }

    /** Ongoing notification for the command-listener foreground service (transparency). */
    fun commandServiceNotification(): android.app.Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, CHANNEL_COMMAND)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Telegram 指令接收中")
            .setContentText("此裝置會依你在 Telegram 的指令發送簡訊")
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /** Shows or hides the ongoing "forwarding is active" banner. [summary] names the active channels. */
    fun showStatus(enabled: Boolean, summary: String) {
        ensureChannels()
        if (!enabled) {
            manager.cancel(ID_STATUS)
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("簡訊轉發運作中")
            .setContentText("收到的簡訊會轉發：$summary")
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        safeNotify(ID_STATUS, notification)
    }

    fun notifyForwarded(fromNumber: String, destination: String, success: Boolean) {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_EVENT)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (success) "已轉發簡訊" else "簡訊轉發失敗")
            .setContentText("來自 $fromNumber → $destination")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        safeNotify(ID_EVENT_BASE + (System.currentTimeMillis() % 1000).toInt(), notification)
    }

    private fun safeNotify(id: Int, notification: android.app.Notification) {
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted (Android 13+); forwarding still works, just no banner.
        }
    }

    companion object {
        const val CHANNEL_STATUS = "forward_status"
        const val CHANNEL_EVENT = "forward_events"
        const val CHANNEL_COMMAND = "forward_commands"
        const val ID_STATUS = 1
        const val ID_COMMAND = 2
        const val ID_EVENT_BASE = 1000
    }
}
