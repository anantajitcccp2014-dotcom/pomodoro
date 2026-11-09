package com.worktimer.pomo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notif {
    private const val CH_TIMER = "timer"
    private const val CH_ALERT = "alert_v2"
    private const val ID_ONGOING = 1
    private const val ID_ALERT = 2

    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.deleteNotificationChannel("alert") // старый канал со стандартным звуком
        nm.createNotificationChannel(
            NotificationChannel(CH_TIMER, "Идёт таймер", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Обратный отсчёт в шторке"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Напоминания", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Пора на перерыв / пора за работу. Звук задаётся в настройках приложения."
                setSound(null, null) // звук играет само приложение
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
            }
        )
    }

    private fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun openPi(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun actionPi(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, ActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** Постоянное уведомление с обратным отсчётом и кнопками. */
    fun updateOngoing(ctx: Context, s: TimerState) {
        val nm = NotificationManagerCompat.from(ctx)
        if (s.phase == Phase.IDLE) {
            nm.cancel(ID_ONGOING)
            return
        }
        if (!canNotify(ctx)) return

        val b = NotificationCompat.Builder(ctx, CH_TIMER)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(Fmt.phaseName(s.phase))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPi(ctx))

        if (s.running) {
            b.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(s.endAt)
                .setShowWhen(true)
                .setContentText("до " + Fmt.clock(s.endAt))
                .addAction(0, "Пауза", actionPi(ctx, ActionReceiver.ACTION_PAUSE, 1))
        } else {
            b.setShowWhen(false)
                .setContentText("Пауза · осталось " + Fmt.timer(s.remainingMs))
                .addAction(0, "Продолжить", actionPi(ctx, ActionReceiver.ACTION_RESUME, 2))
        }
        b.addAction(0, "Пропустить", actionPi(ctx, ActionReceiver.ACTION_SKIP, 3))
        b.addAction(0, "Стоп", actionPi(ctx, ActionReceiver.ACTION_STOP, 4))

        try {
            nm.notify(ID_ONGOING, b.build())
        } catch (e: SecurityException) {
            // нет разрешения на уведомления
        }
    }

    /** Громкое напоминание со звуком и вибрацией. */
    fun alert(ctx: Context, title: String, text: String, startLabel: String?) {
        if (!canNotify(ctx)) return
        val b = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openPi(ctx))
        if (startLabel != null) {
            b.addAction(0, startLabel, actionPi(ctx, ActionReceiver.ACTION_START_NEXT, 5))
        }
        try {
            NotificationManagerCompat.from(ctx).notify(ID_ALERT, b.build())
        } catch (e: SecurityException) {
            // нет разрешения на уведомления
        }
    }

    fun cancelAlert(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ID_ALERT)
    }
}
