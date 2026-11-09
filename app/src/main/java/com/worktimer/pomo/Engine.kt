package com.worktimer.pomo

import android.content.Context
import android.content.SharedPreferences

enum class Phase {
    IDLE, WORK, SHORT_BREAK, LONG_BREAK;

    val isBreak: Boolean get() = this == SHORT_BREAK || this == LONG_BREAK
}

/**
 * Всё состояние таймера. Хранится в SharedPreferences, поэтому переживает
 * закрытие приложения, выгрузку из памяти и перезагрузку телефона.
 */
data class TimerState(
    val phase: Phase = Phase.IDLE,
    val running: Boolean = false,
    val endAt: Long = 0L,          // когда кончится фаза (если идёт)
    val remainingMs: Long = 0L,    // сколько осталось (если на паузе)
    val segmentStart: Long = 0L,   // начало текущего отрезка работы (для учёта времени)
    val cycle: Int = 0,            // сколько рабочих циклов завершено в этом сеансе
    val nextPhase: Phase = Phase.WORK, // что запустит кнопка, когда таймер стоит
) {
    val paused: Boolean get() = phase != Phase.IDLE && !running

    fun remaining(now: Long): Long = when {
        phase == Phase.IDLE -> 0L
        running -> (endAt - now).coerceAtLeast(0L)
        else -> remainingMs
    }
}

object Engine {
    private const val PREFS = "timer_state"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun phaseOf(v: String?, def: Phase): Phase =
        Phase.values().firstOrNull { it.name == v } ?: def

    fun load(ctx: Context): TimerState {
        val p = prefs(ctx)
        return TimerState(
            phase = phaseOf(p.getString("phase", null), Phase.IDLE),
            running = p.getBoolean("running", false),
            endAt = p.getLong("endAt", 0L),
            remainingMs = p.getLong("remaining", 0L),
            segmentStart = p.getLong("segStart", 0L),
            cycle = p.getInt("cycle", 0),
            nextPhase = phaseOf(p.getString("next", null), Phase.WORK),
        )
    }

    private fun save(ctx: Context, s: TimerState) {
        prefs(ctx).edit()
            .putString("phase", s.phase.name)
            .putBoolean("running", s.running)
            .putLong("endAt", s.endAt)
            .putLong("remaining", s.remainingMs)
            .putLong("segStart", s.segmentStart)
            .putInt("cycle", s.cycle)
            .putString("next", s.nextPhase.name)
            .commit()
    }

    fun durationMs(ctx: Context, phase: Phase): Long = 60_000L * when (phase) {
        Phase.WORK -> Config.work(ctx)
        Phase.SHORT_BREAK -> Config.shortBreak(ctx)
        Phase.LONG_BREAK -> Config.longBreak(ctx)
        Phase.IDLE -> 0
    }

    // ---------- действия пользователя ----------

    /** Запустить то, что ждёт своей очереди (работу или перерыв). */
    fun startNext(ctx: Context) {
        val s = load(ctx)
        val next = if (s.phase == Phase.IDLE) s.nextPhase else Phase.WORK
        val now = System.currentTimeMillis()
        Notif.cancelAlert(ctx)
        closeSegment(ctx, s, now)
        begin(ctx, next, s.cycle, now)
    }

    fun pause(ctx: Context) {
        val s = load(ctx)
        if (!s.running || s.phase == Phase.IDLE) return
        val now = System.currentTimeMillis()
        closeSegment(ctx, s, now)
        apply(ctx, s.copy(running = false, remainingMs = (s.endAt - now).coerceAtLeast(0L), segmentStart = 0L))
    }

    fun resume(ctx: Context) {
        val s = load(ctx)
        if (!s.paused) return
        val now = System.currentTimeMillis()
        apply(
            ctx, s.copy(
                running = true,
                endAt = now + s.remainingMs,
                remainingMs = 0L,
                segmentStart = if (s.phase == Phase.WORK) now else 0L,
            )
        )
    }

    fun stop(ctx: Context) {
        val s = load(ctx)
        closeSegment(ctx, s, System.currentTimeMillis())
        Notif.cancelAlert(ctx)
        apply(ctx, TimerState())
    }

    fun skip(ctx: Context) {
        val s = load(ctx)
        if (s.phase == Phase.IDLE) return
        Notif.cancelAlert(ctx)
        finish(ctx, s, System.currentTimeMillis(), natural = false)
    }

    // ---------- события системы ----------

    fun onAlarm(ctx: Context) {
        val s = load(ctx)
        if (!s.running || s.phase == Phase.IDLE) return
        val now = System.currentTimeMillis()
        if (now >= s.endAt - 3_000) finish(ctx, s, now, natural = true)
        else apply(ctx, s) // сработало слишком рано — переставить
    }

    /** После перезагрузки, обновления или открытия приложения: восстановить будильник. */
    fun restore(ctx: Context) {
        val s = load(ctx)
        if (!s.running || s.phase == Phase.IDLE) {
            Notif.updateOngoing(ctx, s)
            return
        }
        if (System.currentTimeMillis() >= s.endAt) onAlarm(ctx) else apply(ctx, s)
    }

    // ---------- внутреннее ----------

    private fun begin(ctx: Context, phase: Phase, cycle: Int, now: Long) {
        apply(
            ctx, TimerState(
                phase = phase,
                running = true,
                endAt = now + durationMs(ctx, phase),
                segmentStart = if (phase == Phase.WORK) now else 0L,
                cycle = cycle,
            )
        )
    }

    private fun closeSegment(ctx: Context, s: TimerState, now: Long) {
        if (s.phase == Phase.WORK && s.running && s.segmentStart > 0L) {
            Stats.addWork(ctx, s.segmentStart, minOf(now, s.endAt))
        }
    }

    private fun finish(ctx: Context, s: TimerState, now: Long, natural: Boolean) {
        val end = if (natural) minOf(now, s.endAt) else now
        if (s.phase == Phase.WORK) {
            if (s.running && s.segmentStart > 0L) Stats.addWork(ctx, s.segmentStart, end)
            if (natural) Stats.addPomodoro(ctx, end)
            val cycle = s.cycle + 1
            val brk = if (cycle % Config.cycles(ctx) == 0) Phase.LONG_BREAK else Phase.SHORT_BREAK
            val auto = Config.autoBreak(ctx) || !natural
            if (natural) {
                val mins = durationMs(ctx, brk) / 60_000L
                val kind = if (brk == Phase.LONG_BREAK) "Длинный перерыв" else "Перерыв"
                val tail = if (auto) " Таймер перерыва уже идёт." else ""
                Notif.alert(
                    ctx, "Пора сделать перерыв", "$kind — $mins мин.$tail",
                    if (auto) null else "Начать перерыв"
                )
            }
            if (auto) begin(ctx, brk, cycle, now)
            else apply(ctx, TimerState(cycle = cycle, nextPhase = brk))
        } else {
            val auto = Config.autoWork(ctx) || !natural
            if (natural) {
                val tail = if (auto) " Таймер работы уже идёт." else ""
                Notif.alert(
                    ctx, "Перерыв окончен", "Пора за работу.$tail",
                    if (auto) null else "Начать работу"
                )
            }
            if (auto) begin(ctx, Phase.WORK, s.cycle, now)
            else apply(ctx, TimerState(cycle = s.cycle, nextPhase = Phase.WORK))
        }
    }

    private fun apply(ctx: Context, s: TimerState) {
        save(ctx, s)
        if (s.running && s.phase != Phase.IDLE) Alarms.schedule(ctx, s.endAt) else Alarms.cancel(ctx)
        Notif.updateOngoing(ctx, s)
    }
}
