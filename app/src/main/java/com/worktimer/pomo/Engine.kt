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
 *
 * overtime = фаза закончилась, но таймер считает дальше в прямом отсчёте
 * (для работы это время засчитывается).
 */
data class TimerState(
    val phase: Phase = Phase.IDLE,
    val running: Boolean = false,
    val endAt: Long = 0L,          // когда кончается (кончилась) фаза
    val remainingMs: Long = 0L,    // сколько осталось (если на паузе)
    val segmentStart: Long = 0L,   // начало текущего отрезка работы (для учёта времени)
    val cycle: Int = 0,            // сколько рабочих циклов завершено в этом сеансе
    val nextPhase: Phase = Phase.WORK, // что запустит кнопка
    val overtime: Boolean = false,
    val tip: String = "",          // фраза/подсказка для текущей фазы
) {
    val paused: Boolean get() = phase != Phase.IDLE && !running

    fun remaining(now: Long): Long = when {
        phase == Phase.IDLE || overtime -> 0L
        running -> (endAt - now).coerceAtLeast(0L)
        else -> remainingMs
    }

    fun overtimeMs(now: Long): Long =
        if (overtime) (minOf(now, endAt + Engine.OVERTIME_CAP_MS) - endAt).coerceAtLeast(0L) else 0L
}

object Engine {
    private const val PREFS = "timer_state"

    /** Доработка автоматически останавливается через час, если про неё забыли. */
    const val OVERTIME_CAP_MS = 60 * 60_000L

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
            overtime = p.getBoolean("overtime", false),
            tip = p.getString("tip", "") ?: "",
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
            .putBoolean("overtime", s.overtime)
            .putString("tip", s.tip)
            .commit()
    }

    fun durationMs(ctx: Context, phase: Phase): Long = 60_000L * when (phase) {
        Phase.WORK -> Config.work(ctx)
        Phase.SHORT_BREAK -> Config.shortBreak(ctx)
        Phase.LONG_BREAK -> Config.longBreak(ctx)
        Phase.IDLE -> 0
    }

    /** Текущий, ещё не записанный отрезок работы (для живых цифр на экране). */
    fun liveSegment(s: TimerState, now: Long): Pair<Long, Long>? {
        if (s.phase != Phase.WORK || !s.running || s.segmentStart <= 0L) return null
        val end = if (s.overtime) minOf(now, s.endAt + OVERTIME_CAP_MS) else minOf(now, s.endAt)
        return if (end > s.segmentStart) s.segmentStart to end else null
    }

    // ---------- действия пользователя ----------

    /** Запустить то, что ждёт своей очереди (работу или перерыв). */
    fun startNext(ctx: Context) {
        val s = load(ctx)
        val next = if (s.phase == Phase.IDLE || s.overtime) s.nextPhase else Phase.WORK
        val now = System.currentTimeMillis()
        Notif.cancelAlert(ctx)
        closeSegment(ctx, s, now)
        val tip = when {
            next.isBreak && s.overtime && s.tip.isNotEmpty() -> s.tip
            next.isBreak -> Phrases.breakTip(next)
            else -> Phrases.start()
        }
        begin(ctx, next, s.cycle, now, tip)
    }

    fun pause(ctx: Context) {
        val s = load(ctx)
        if (!s.running || s.phase == Phase.IDLE || s.overtime) return
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
        if (s.overtime) {
            startNext(ctx)
            return
        }
        Notif.cancelAlert(ctx)
        finish(ctx, s, System.currentTimeMillis(), natural = false)
    }

    /** Сменить проект. Уже отработанное время остаётся за прежним проектом. */
    fun switchProject(ctx: Context, id: String) {
        val s = load(ctx)
        val now = System.currentTimeMillis()
        if (liveSegment(s, now) != null) {
            closeSegment(ctx, s, now)
            Projects.setCurrent(ctx, id)
            save(ctx, s.copy(segmentStart = now))
        } else {
            Projects.setCurrent(ctx, id)
            save(ctx, s) // чтобы экран обновился
        }
    }

    // ---------- события системы ----------

    /** Возвращает фазу, которая только что закончилась (или null, если ничего не закончилось). */
    fun onAlarm(ctx: Context): Phase? {
        val s = load(ctx)
        if (!s.running || s.phase == Phase.IDLE) return null
        val now = System.currentTimeMillis()
        if (s.overtime) {
            if (now >= s.endAt + OVERTIME_CAP_MS - 3_000) {
                // про доработку забыли — останавливаем, лишнее не засчитываем
                closeSegment(ctx, s, now)
                Notif.alert(
                    ctx, "Отсчёт остановлен",
                    "Прошёл час сверх таймера, отсчёт остановлен. Засчитано время до этого момента.",
                    if (s.nextPhase.isBreak) "Начать перерыв" else "Начать работу"
                )
                apply(ctx, TimerState(cycle = s.cycle, nextPhase = s.nextPhase, tip = s.tip))
            } else {
                apply(ctx, s)
            }
            return null
        }
        if (now >= s.endAt - 3_000) {
            finish(ctx, s, now, natural = true)
            return s.phase
        }
        apply(ctx, s) // сработало слишком рано — переставить
        return null
    }

    /** После перезагрузки, обновления или открытия приложения: восстановить будильник. */
    fun restore(ctx: Context) {
        val s = load(ctx)
        if (!s.running || s.phase == Phase.IDLE) {
            Notif.updateOngoing(ctx, s)
            return
        }
        val due = if (s.overtime) s.endAt + OVERTIME_CAP_MS else s.endAt
        if (System.currentTimeMillis() >= due) onAlarm(ctx) else apply(ctx, s)
    }

    // ---------- внутреннее ----------

    private fun begin(ctx: Context, phase: Phase, cycle: Int, now: Long, tip: String) {
        apply(
            ctx, TimerState(
                phase = phase,
                running = true,
                endAt = now + durationMs(ctx, phase),
                segmentStart = if (phase == Phase.WORK) now else 0L,
                cycle = cycle,
                tip = tip,
            )
        )
    }

    private fun closeSegment(ctx: Context, s: TimerState, now: Long) {
        val seg = liveSegment(s, now) ?: return
        Stats.addWork(ctx, seg.first, seg.second, Projects.currentId(ctx))
    }

    private fun finish(ctx: Context, s: TimerState, now: Long, natural: Boolean) {
        if (s.phase == Phase.WORK) {
            val end = if (natural) minOf(now, s.endAt) else now
            if (natural) Stats.addPomodoro(ctx, end, Projects.currentId(ctx))
            val cycle = s.cycle + 1
            val brk = if (cycle % Config.cycles(ctx) == 0) Phase.LONG_BREAK else Phase.SHORT_BREAK
            val tip = Phrases.breakTip(brk)
            val auto = Config.autoBreak(ctx) || !natural
            if (natural) {
                val mins = durationMs(ctx, brk) / 60_000L
                val kind = if (brk == Phase.LONG_BREAK) "Длинный перерыв" else "Перерыв"
                val text = if (auto) "$kind $mins мин. $tip"
                else "Пора на перерыв ($mins мин). $tip\nЕсли нужно доделать — время идёт дальше и засчитывается."
                Notif.alert(ctx, Phrases.praise(), text, if (auto) null else "Начать перерыв")
            }
            if (auto) {
                if (s.running && s.segmentStart > 0L) {
                    Stats.addWork(ctx, s.segmentStart, end, Projects.currentId(ctx))
                }
                begin(ctx, brk, cycle, now, tip)
            } else {
                // доработка: отрезок работы продолжается
                apply(ctx, s.copy(overtime = true, cycle = cycle, nextPhase = brk, tip = tip))
            }
        } else {
            val phrase = Phrases.backToWork()
            val auto = Config.autoWork(ctx) || !natural
            if (natural) {
                Notif.alert(ctx, "Перерыв окончен", phrase, if (auto) null else "Начать работу")
            }
            if (auto) begin(ctx, Phase.WORK, s.cycle, now, Phrases.start())
            else apply(ctx, s.copy(overtime = true, nextPhase = Phase.WORK, tip = phrase))
        }
    }

    private fun apply(ctx: Context, s: TimerState) {
        save(ctx, s)
        when {
            !s.running || s.phase == Phase.IDLE -> Alarms.cancel(ctx)
            s.overtime -> Alarms.schedule(ctx, s.endAt + OVERTIME_CAP_MS)
            else -> Alarms.schedule(ctx, s.endAt)
        }
        Notif.updateOngoing(ctx, s)
    }
}
