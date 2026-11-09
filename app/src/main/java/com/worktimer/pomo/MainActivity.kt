package com.worktimer.pomo

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var phaseText: TextView
    private lateinit var timerText: TextView
    private lateinit var cycleText: TextView
    private lateinit var hintText: TextView
    private lateinit var mainButton: MaterialButton
    private lateinit var skipButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var todayText: TextView
    private lateinit var weekText: TextView
    private lateinit var daysText: TextView

    private var keepingScreen = false
    private val ru: Locale = Locale.forLanguageTag("ru")
    private val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        phaseText = findViewById(R.id.phaseText)
        timerText = findViewById(R.id.timerText)
        cycleText = findViewById(R.id.cycleText)
        hintText = findViewById(R.id.hintText)
        mainButton = findViewById(R.id.mainButton)
        skipButton = findViewById(R.id.skipButton)
        stopButton = findViewById(R.id.stopButton)
        todayText = findViewById(R.id.todayText)
        weekText = findViewById(R.id.weekText)
        daysText = findViewById(R.id.daysText)

        mainButton.setOnClickListener {
            val s = Engine.load(this)
            when {
                s.phase == Phase.IDLE -> Engine.startNext(this)
                s.running -> Engine.pause(this)
                else -> Engine.resume(this)
            }
            render()
        }
        skipButton.setOnClickListener { Engine.skip(this); render() }
        stopButton.setOnClickListener { Engine.stop(this); render() }
        findViewById<MaterialButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Engine.restore(this)
        Engine.prefs(this).registerOnSharedPreferenceChangeListener(this)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        Engine.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        handler.removeCallbacks(ticker)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        render()
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val s = Engine.load(this)
        val shown = if (s.phase == Phase.IDLE) s.nextPhase else s.phase
        val color = ContextCompat.getColor(this, if (shown.isBreak) R.color.leaf else R.color.tomato)

        phaseText.text = when {
            s.phase == Phase.IDLE && s.cycle == 0 && s.nextPhase == Phase.WORK -> "Готов к работе"
            s.phase == Phase.IDLE -> "Дальше: " + Fmt.phaseName(s.nextPhase).lowercase(ru)
            s.running -> Fmt.phaseName(s.phase)
            else -> Fmt.phaseName(s.phase) + " · пауза"
        }
        phaseText.setTextColor(color)

        val ms = if (s.phase == Phase.IDLE) Engine.durationMs(this, s.nextPhase) else s.remaining(now)
        timerText.text = Fmt.timer(ms)
        timerText.setTextColor(color)
        timerText.alpha = if (s.running) 1f else 0.55f

        val cycles = Config.cycles(this)
        val filled = if (s.cycle > 0 && s.cycle % cycles == 0 && shown == Phase.LONG_BREAK) cycles
        else s.cycle % cycles
        cycleText.text = "●".repeat(filled) + "○".repeat(cycles - filled)
        cycleText.setTextColor(color)

        hintText.text = when {
            s.phase == Phase.IDLE && s.nextPhase.isBreak -> "Работа закончена — отдохни"
            s.phase == Phase.IDLE && s.cycle > 0 -> "Перерыв окончен — пора за работу"
            s.running -> "закончится в " + Fmt.clock(s.endAt)
            s.phase != Phase.IDLE -> "на паузе"
            else -> " "
        }

        mainButton.text = when {
            s.phase == Phase.IDLE -> if (s.nextPhase.isBreak) "Начать перерыв" else "Начать работу"
            s.running -> "Пауза"
            else -> "Продолжить"
        }
        mainButton.backgroundTintList = ColorStateList.valueOf(color)
        skipButton.isEnabled = s.phase != Phase.IDLE
        stopButton.isEnabled = s.phase != Phase.IDLE || s.cycle > 0

        // Не гасить экран, пока таймер идёт и приложение открыто.
        val keep = s.running && Config.keepScreen(this)
        if (keep != keepingScreen) {
            if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepingScreen = keep
        }

        renderStats(s, now)
    }

    private fun renderStats(s: TimerState, now: Long) {
        // Текущий, ещё не записанный отрезок работы тоже учитываем — цифры растут на глазах.
        val live = if (s.phase == Phase.WORK && s.running && s.segmentStart > 0L)
            Stats.split(s.segmentStart, minOf(now, s.endAt)) else emptyMap()

        fun work(d: LocalDate): Long = Stats.workMs(this, d) + (live[d] ?: 0L)

        val today = LocalDate.now()
        todayText.text = "Сегодня: ${Fmt.duration(work(today))} · помидоров: ${Stats.pomodoros(this, today)}"

        var week = 0L
        var d = today.with(DayOfWeek.MONDAY)
        while (!d.isAfter(today)) {
            week += work(d)
            d = d.plusDays(1)
        }
        weekText.text = "Эта неделя: ${Fmt.duration(week)}"

        daysText.text = (0..6).joinToString("\n") { i ->
            val day = today.minusDays(i.toLong())
            val name = day.dayOfWeek.getDisplayName(TextStyle.SHORT, ru)
            val w = work(day)
            val n = Stats.pomodoros(this, day)
            val value = if (w == 0L && n == 0) "—" else "${Fmt.duration(w)} · 🍅 $n"
            "$name ${day.format(dayFmt)}   $value"
        }
    }
}
