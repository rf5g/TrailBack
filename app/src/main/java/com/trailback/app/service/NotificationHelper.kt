package com.trailback.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.trailback.app.R
import com.trailback.app.ui.map.MapActivity

/**
 * Уведомления по п.9 ТЗ:
 * - при "Старт" уведомление не нужно (только foreground-уведомление сервиса
 *   для соответствия требованиям Android, без доп. акцента);
 * - при "Стоп" и при входе в режим "Домой" — обязательное информационное уведомление;
 * - "Вы вернулись!" — уведомление, если приложение свёрнуто (если открыто — диалог
 *   показывает Activity напрямую, см. HomeArrivalController).
 */
class NotificationHelper(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TRACKING,
                    context.getString(R.string.notification_channel_tracking),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.notification_channel_alerts),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    /** Постоянное foreground-уведомление сервиса (обязательное системное требование). */
    fun buildForegroundNotification(contentText: String): android.app.Notification {
        val openAppIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MapActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_notification_tracking)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    fun notifyStopped() {
        show(
            id = ID_STOPPED,
            title = context.getString(R.string.notification_stopped_title),
            text = context.getString(R.string.notification_stopped_text)
        )
    }

    fun notifyReturningStarted() {
        show(
            id = ID_RETURNING,
            title = context.getString(R.string.notification_returning_title),
            text = context.getString(R.string.notification_returning_text)
        )
    }

    /** Показывается, только если приложение свёрнуто — тап открывает диалог подтверждения. */
    fun notifyArrivedHome() {
        val openAppIntent = PendingIntent.getActivity(
            context, 1,
            Intent(context, MapActivity::class.java).apply {
                action = MapActivity.ACTION_SHOW_ARRIVED_DIALOG
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification_arrived)
            .setContentTitle(context.getString(R.string.notification_arrived_title))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(ID_ARRIVED, notification)
    }

    private fun show(id: Int, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification_tracking)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking_channel"
        const val CHANNEL_ALERTS = "alerts_channel"
        const val FOREGROUND_NOTIFICATION_ID = 1001

        private const val ID_STOPPED = 1002
        private const val ID_RETURNING = 1003
        private const val ID_ARRIVED = 1004
    }
}
