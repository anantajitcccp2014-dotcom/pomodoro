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
    fun autoBreak(c: Context) = p(c).getBoolean("autoBreak2", false)
    fun autoWork(c: Context) = p(c).getBoolean("autoWork", false)
    fun keepScreen(c: Context) = p(c).getBoolean("keepScreen", true)
    fun alarmVolume(c: Context) = p(c).getBoolean("alarmVolume", true)
    /** Дневная цель в минутах (0 — без цели). */
    fun dailyGoalMin(c: Context) = p(c).getInt("dailyGoal", 240)
    /** Большая цель в часах (0 — без цели). */
    fun bigGoalHours(c: Context) = p(c).getInt("bigGoal", 1000)

    /** key: "work" — звук конца работы, "break" — звук конца перерыва. */
    fun sound(c: Context, key: String): String =
        p(c).getString("sound_$key", null)
            ?: if (key == "work") Sound.DEFAULT_WORK_END else Sound.DEFAULT_BREAK_END

    fun soundName(c: Context, key: String): String {
        val spec = sound(c, key)
        if (spec == "none") return "Без звука"
        if (spec.startsWith("raw:")) {
            val id = spec.removePrefix("raw:")
            return Sound.BUILTIN.firstOrNull { it.id == id }?.title ?: "Встроенный звук"
        }
        return p(c).getString("soundName_$key", null) ?: "Свой звук"
    }

    fun setSound(c: Context, key: String, spec: String, name: String?) {
        p(c).edit()
            .putString("sound_$key", spec)
            .putString("soundName_$key", name)
            .apply()
    }

    fun save(
        c: Context, work: Int, short: Int, long: Int, cycles: Int,
        autoBreak: Boolean, autoWork: Boolean, keepScreen: Boolean, alarmVolume: Boolean,
        dailyGoalMin: Int, bigGoalHours: Int,
    ) {
        p(c).edit()
            .putInt("work", work)
            .putInt("short", short)
            .putInt("long", long)
            .putInt("cycles", cycles)
            .putBoolean("autoBreak2", autoBreak)
            .putInt("dailyGoal", dailyGoalMin)
            .putInt("bigGoal", bigGoalHours)
            .putBoolean("autoWork", autoWork)
            .putBoolean("keepScreen", keepScreen)
            .putBoolean("alarmVolume", alarmVolume)
            .apply()
    }
}

/**
 * Учёт рабочего времени по дням и проектам.
 * Ключи: "w_<дата>" — основной проект, "w_<дата>|<id>" — другие (так же "p_" для завершённых гхатик).
 */
object Stats {
    private fun p(c: Context) = c.getSharedPreferences("stats", Context.MODE_PRIVATE)

    private fun key(prefix: String, day: LocalDate, project: String) =
        if (project == Projects.MAIN) "${prefix}_$day" else "${prefix}_$day|$project"

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

    fun addWork(c: Context, from: Long, to: Long, project: String) {
        val parts = split(from, to)
        if (parts.isEmpty()) return
        val prefs = p(c)
        val e = prefs.edit()
        for ((day, ms) in parts) {
            val k = key("w", day, project)
            e.putLong(k, prefs.getLong(k, 0L) + ms)
        }
        e.commit()
    }

    fun addPomodoro(c: Context, at: Long, project: String) {
        val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
        val k = key("p", day, project)
        val prefs = p(c)
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).commit()
    }

    /** Все записи: день -> (проект -> значение). */
    private fun <T> loadAll(c: Context, prefix: String, read: (Any?) -> T?): MutableMap<LocalDate, MutableMap<String, T>> {
        val out = mutableMapOf<LocalDate, MutableMap<String, T>>()
        for ((k, v) in p(c).all) {
            if (!k.startsWith(prefix + "_")) continue
            val rest = k.substring(prefix.length + 1)
            val bar = rest.indexOf('|')
            val dayStr = if (bar >= 0) rest.substring(0, bar) else rest
            val project = if (bar >= 0) rest.substring(bar + 1) else Projects.MAIN
            val day = runCatching { LocalDate.parse(dayStr) }.getOrNull() ?: continue
            val value = read(v) ?: continue
            out.getOrPut(day) { mutableMapOf() }[project] = value
        }
        return out
    }

    fun loadWork(c: Context): MutableMap<LocalDate, MutableMap<String, Long>> =
        loadAll(c, "w") { (it as? Number)?.toLong() }

    fun loadPomodoros(c: Context): MutableMap<LocalDate, MutableMap<String, Int>> =
        loadAll(c, "p") { (it as? Number)?.toInt() }

    /** Рабочее время вместе с идущим прямо сейчас отрезком. */
    fun loadWorkLive(c: Context): MutableMap<LocalDate, MutableMap<String, Long>> {
        val data = loadWork(c)
        val seg = Engine.liveSegment(Engine.load(c), System.currentTimeMillis()) ?: return data
        val project = Projects.currentId(c)
        for ((day, ms) in split(seg.first, seg.second)) {
            val m = data.getOrPut(day) { mutableMapOf() }
            m[project] = (m[project] ?: 0L) + ms
        }
        return data
    }
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
        val ended = Engine.onAlarm(context) ?: return
        val key = if (ended == Phase.WORK) "work" else "break"
        val spec = Config.sound(context, key)
        if (spec == "none") return
        // goAsync держит телефон «проснувшимся», пока играет звук.
        val pending = goAsync()
        val started = Sound.play(context, spec) { pending.finish() } ||
            Sound.play(context, if (key == "work") Sound.DEFAULT_WORK_END else Sound.DEFAULT_BREAK_END) {
                pending.finish()
            }
        if (!started) pending.finish()
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

    /** Подпись оси графика. */
    fun axis(ms: Long): String {
        val min = ms / 60_000L
        return when {
            min == 0L -> "0"
            min % 60 == 0L -> "${min / 60} ч"
            min >= 60 -> String.format(Locale.ROOT, "%.1f ч", min / 60.0).replace('.', ',')
            else -> "$min мин"
        }
    }

    fun hours(ms: Long): String {
        val h = ms / 3_600_000.0
        return if (h >= 10 || h == Math.floor(h)) "${h.toLong()}"
        else String.format(Locale.ROOT, "%.1f", h).replace('.', ',')
    }

    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
    fun clock(ms: Long): String =
        clockFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}
