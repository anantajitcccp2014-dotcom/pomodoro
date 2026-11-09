package com.worktimer.pomo

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notif.init(this)
    }
}

/** Настройки пользователя. */
object Config {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun work(c: Context) = p(c).getInt("work", 25)
    fun shortBreak(c: Context) = p(c).getInt("short", 5)
    fun longBreak(c: Context) = p(c).getInt("long", 15)
    fun cycles(c: Context) = p(c).getInt("cycles", 4).coerceAtLeast(1)
    fun autoBreak(c: Context) = p(c).getBoolean("autoBreak", true)
    fun autoWork(c: Context) = p(c).getBoolean("autoWork", false)

    fun save(
        c: Context, work: Int, short: Int, long: Int, cycles: Int,
        autoBreak: Boolean, autoWork: Boolean,
    ) {
        p(c).edit()
            .putInt("work", work)
            .putInt("short", short)
            .putInt("long", long)
            .putInt("cycles", cycles)
            .putBoolean("autoBreak", autoBreak)
            .putBoolean("autoWork", autoWork)
            .apply()
    }
}

/** Учёт рабочего времени по дням. */
object Stats {
    private fun p(c: Context) = c.getSharedPreferences("stats", Context.MODE_PRIVATE)

    /** Разбивает отрезок времени по календарным дням (если он переходит через полночь). */
    fun split(from: Long, to: Long): Map<LocalDate, Long> {
        val out = linkedMapOf<LocalDate, Long>()
        if (to <= from) return out
        val zone = ZoneId.systemDefault()
        var start = from
        while (start < to) {
            val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(to, nextMidnight)
            out[day] = (out[day] ?: 0L) + (end - start)
            start = end
        }
        return out
    }

    fun addWork(c: Context, from: Long, to: Long) {
        val parts = split(from, to)
        if (parts.isEmpty()) return
        val prefs = p(c)
        val e = prefs.edit()
        for ((day, ms) in parts) {
            val key = "w_$day"
            e.putLong(key, prefs.getLong(key, 0L) + ms)
        }
        e.commit()
    }

    fun addPomodoro(c: Context, at: Long) {
        val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
        val key = "p_$day"
        val prefs = p(c)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).commit()
    }

    fun workMs(c: Context, day: LocalDate): Long = p(c).getLong("w_$day", 0L)
    fun pomodoros(c: Context, day: LocalDate): Int = p(c).getInt("p_$day", 0)
}

/** Будильник, который срабатывает в конце фазы даже при выключенном экране. */
object Alarms {
    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 100, Intent(ctx, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(ctx: Context, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, Notif.openPi(ctx)), pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Engine.onAlarm(context)
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE -> Engine.pause(context)
            ACTION_RESUME -> Engine.resume(context)
            ACTION_SKIP -> Engine.skip(context)
            ACTION_STOP -> Engine.stop(context)
            ACTION_START_NEXT -> Engine.startNext(context)
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.worktimer.pomo.PAUSE"
        const val ACTION_RESUME = "com.worktimer.pomo.RESUME"
        const val ACTION_SKIP = "com.worktimer.pomo.SKIP"
        const val ACTION_STOP = "com.worktimer.pomo.STOP"
        const val ACTION_START_NEXT = "com.worktimer.pomo.START_NEXT"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Engine.restore(context)
    }
}

object Fmt {
    fun phaseName(p: Phase): String = when (p) {
        Phase.WORK -> "Работа"
        Phase.SHORT_BREAK -> "Короткий перерыв"
        Phase.LONG_BREAK -> "Длинный перерыв"
        Phase.IDLE -> "Остановлено"
    }

    fun timer(ms: Long): String {
        val total = (ms + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    fun duration(ms: Long): String {
        val min = ms / 60_000L
        val h = min / 60
        val m = min % 60
        return when {
            h > 0 -> "$h ч $m мин"
            else -> "$m мин"
        }
    }

    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
    fun clock(ms: Long): String =
        clockFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}
