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
 * Уведомления (пересмотрено по решению — не заваливать пользователя
 * отдельными всплывающими уведомлениями на каждое действие):
 * - "Старт"/"Стоп"/"Домой" НЕ создают отдельных уведомлений — вместо этого
 *   обновляется текст ОДНОГО постоянного foreground-уведомления сервиса
 *   (см. updateForegroundStatus) — статус виден в шторке всегда, без спама;
 * - "Вы вернулись!" — единственное настоящее push-уведомление, потому что
 *   это редкое, важное и требующее действия событие (актуально, только
 *   если приложение свёрнуто — если открыто, диалог показывает сама Activity).
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
        // НОВОЕ: кнопка "Выход" в постоянном уведомлении — открывает
        // MenuActivity с флагом автозапуска onExitTapped(), т.е. ведёт себя
        // ТОЧНО так же, как кнопка "Выход" в меню (та же блокировка в
        // режиме "Домой", тот же диалог подтверждения) — см. решение по ТЗ.
        // FLAG_ACTIVITY_NEW_TASK обязателен: PendingIntent может сработать,
        // когда процесс приложения уже не запущен.
        val exitIntent = Intent(context, com.trailback.app.ui.menu.MenuActivity::class.java).apply {
            putExtra(com.trailback.app.ui.menu.MenuActivity.EXTRA_AUTO_EXIT, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val exitPendingIntent = PendingIntent.getActivity(
            context, 3, exitIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_notification_tracking)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_cancel_direction, context.getString(R.string.notification_exit_action), exitPendingIntent)
            .setOngoing(true)
            .build()
    }
    /**
     * Обновляет текст уже показанного постоянного уведомления (та же
     * ID = FOREGROUND_NOTIFICATION_ID) — пользователь видит смену статуса
     * ("Идёт запись маршрута" → "Возврат к точке старта" → "Готово") прямо
     * в существующей строке шторки, без новых всплывающих сообщений.
     */
    fun updateForegroundStatus(contentText: String) {
        manager.notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(contentText))
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
    companion object {
        const val CHANNEL_TRACKING = "tracking_channel"
        const val CHANNEL_ALERTS = "alerts_channel"
        const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val ID_ARRIVED = 1004
    }
}
