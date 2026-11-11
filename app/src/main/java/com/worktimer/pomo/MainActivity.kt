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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var projectButton: MaterialButton
    private lateinit var phaseText: TextView
    private lateinit var timerText: TextView
    private lateinit var cycleText: TextView
    private lateinit var messageText: TextView
    private lateinit var mainButton: MaterialButton
    private lateinit var skipButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var todayText: TextView
    private lateinit var todayDetails: TextView
    private lateinit var weekText: TextView
    private lateinit var dailyProgress: LinearProgressIndicator
    private lateinit var bigGoalText: TextView
    private lateinit var bigProgress: LinearProgressIndicator
    private lateinit var bigGoalDetails: TextView

    private var workData: Map<LocalDate, Map<String, Long>> = emptyMap()
    private var pomoData: Map<LocalDate, Map<String, Int>> = emptyMap()
    private var keepingScreen = false
    private var idlePhrase = Phrases.start()
    private val ru: Locale = Locale.forLanguageTag("ru")

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

        projectButton = findViewById(R.id.projectButton)
        phaseText = findViewById(R.id.phaseText)
        timerText = findViewById(R.id.timerText)
        cycleText = findViewById(R.id.cycleText)
        messageText = findViewById(R.id.messageText)
        mainButton = findViewById(R.id.mainButton)
        skipButton = findViewById(R.id.skipButton)
        stopButton = findViewById(R.id.stopButton)
        todayText = findViewById(R.id.todayText)
        todayDetails = findViewById(R.id.todayDetails)
        weekText = findViewById(R.id.weekText)
        dailyProgress = findViewById(R.id.dailyProgress)
        bigGoalText = findViewById(R.id.bigGoalText)
        bigProgress = findViewById(R.id.bigProgress)
        bigGoalDetails = findViewById(R.id.bigGoalDetails)

        mainButton.setOnClickListener {
            val s = Engine.load(this)
            when {
                s.phase == Phase.IDLE || s.overtime -> Engine.startNext(this)
                s.running -> Engine.pause(this)
                else -> Engine.resume(this)
            }
            refreshData()
            render()
        }
        skipButton.setOnClickListener { Engine.skip(this); refreshData(); render() }
        stopButton.setOnClickListener { Engine.stop(this); refreshData(); render() }
        projectButton.setOnClickListener { chooseProject() }
        findViewById<MaterialButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.statsButton).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
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
        idlePhrase = Phrases.start()
        refreshData()
        Engine.prefs(this).registerOnSharedPreferenceChangeListener(this)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        Engine.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        handler.removeCallbacks(ticker)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        refreshData()
        render()
    }

    private fun refreshData() {
        workData = Stats.loadWork(this)
        pomoData = Stats.loadPomodoros(this)
    }

    // ---------------- отрисовка ----------------

    private fun render() {
        val now = System.currentTimeMillis()
        val s = Engine.load(this)
        val colorPhase = if (s.phase == Phase.IDLE) s.nextPhase else s.phase
        val color = ContextCompat.getColor(this, if (colorPhase.isBreak) R.color.rest else R.color.work)
        val onColor = ContextCompat.getColor(this, R.color.on_phase)
        val waiting = s.phase == Phase.IDLE || s.overtime

        renderProject()

        phaseText.text = when {
            s.phase == Phase.IDLE && s.cycle == 0 && s.nextPhase == Phase.WORK -> "Готов к работе"
            s.phase == Phase.IDLE -> "Дальше: " + Fmt.phaseName(s.nextPhase).lowercase(ru)
            s.overtime && s.phase == Phase.WORK -> "Время вышло · доработка"
            s.overtime -> "Перерыв затянулся"
            s.running -> Fmt.phaseName(s.phase)
            else -> Fmt.phaseName(s.phase) + " · пауза"
        }
        phaseText.setTextColor(color)

        timerText.text = when {
            s.overtime -> "+" + Fmt.timer(s.overtimeMs(now))
            s.phase == Phase.IDLE -> Fmt.timer(Engine.durationMs(this, s.nextPhase))
            else -> Fmt.timer(s.remaining(now))
        }
        timerText.setTextColor(color)
        timerText.alpha = if (s.running) 1f else 0.55f

        val cycles = Config.cycles(this)
        val longNow = s.phase == Phase.LONG_BREAK || (waiting && s.nextPhase == Phase.LONG_BREAK)
        val filled = if (s.cycle > 0 && s.cycle % cycles == 0 && longNow) cycles else s.cycle % cycles
        cycleText.text = "●".repeat(filled) + "○".repeat(cycles - filled)
        cycleText.setTextColor(color)

        messageText.text = when {
            s.phase == Phase.IDLE && s.nextPhase.isBreak ->
                "Пора на перерыв.\n" + s.tip.ifEmpty { Phrases.breakTip(s.nextPhase) }
            s.phase == Phase.IDLE && s.cycle > 0 -> s.tip.ifEmpty { "Пора за работу." }
            s.phase == Phase.IDLE -> idlePhrase
            s.overtime && s.phase == Phase.WORK ->
                "Можно доделать — это время засчитывается.\nНа перерыве: " + s.tip
            s.overtime -> s.tip
            !s.running -> "На паузе"
            s.phase == Phase.WORK -> s.tip + "\nзакончится в " + Fmt.clock(s.endAt)
            else -> s.tip + "\nперерыв до " + Fmt.clock(s.endAt)
        }

        mainButton.text = when {
            waiting -> if (s.nextPhase.isBreak) "Начать перерыв" else "Начать работу"
            s.running -> "Пауза"
            else -> "Продолжить"
        }
        // кнопка окрашена в цвет того, что она запустит
        val btnPhase = if (waiting) s.nextPhase else s.phase
        mainButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (btnPhase.isBreak) R.color.rest else R.color.work)
        )
        mainButton.setTextColor(onColor)
        skipButton.isEnabled = s.phase != Phase.IDLE && !s.overtime
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

    private fun renderProject() {
        val pr = Projects.current(this)
        val text = SpannableString("●  ${pr.name}  ▾")
        text.setSpan(ForegroundColorSpan(Projects.colorOf(pr)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        projectButton.text = text
    }

    private fun renderStats(s: TimerState, now: Long) {
        val workColor = ContextCompat.getColor(this, R.color.work)
        val restColor = ContextCompat.getColor(this, R.color.rest)
        val seg = Engine.liveSegment(s, now)
        val live = if (seg != null) Stats.split(seg.first, seg.second) else emptyMap()
        val currentId = Projects.currentId(this)

        fun day(d: LocalDate): Long = (workData[d]?.values?.sum() ?: 0L) + (live[d] ?: 0L)

        val today = LocalDate.now()
        val todayMs = day(today)
        val goal = Config.dailyGoalMin(this) * 60_000L
        if (goal > 0) {
            val done = todayMs >= goal
            todayText.text = "Сегодня: ${Fmt.duration(todayMs)} из ${Fmt.duration(goal)}" + if (done) "  ✓" else ""
            dailyProgress.visibility = View.VISIBLE
            dailyProgress.setIndicatorColor(if (done) restColor else workColor)
            dailyProgress.setProgressCompat((todayMs * 1000 / goal).coerceAtMost(1000L).toInt(), false)
        } else {
            todayText.text = "Сегодня: ${Fmt.duration(todayMs)}"
            dailyProgress.visibility = View.GONE
        }

        val pomos = pomoData[today]?.values?.sum() ?: 0
        val projects = Projects.all(this)
        val todayProject = (workData[today]?.get(currentId) ?: 0L) + (live[today] ?: 0L)
        todayDetails.text = buildString {
            append("гхатик: $pomos")
            if (projects.size > 1) append(" · «${Projects.current(this@MainActivity).name}»: ${Fmt.duration(todayProject)}")
        }

        var week = 0L
        var d = today.with(DayOfWeek.MONDAY)
        while (!d.isAfter(today)) {
            week += day(d)
            d = d.plusDays(1)
        }
        weekText.text = "Эта неделя: ${Fmt.duration(week)}"

        val bigHours = Config.bigGoalHours(this)
        if (bigHours > 0) {
            val all = workData.values.sumOf { it.values.sum() } + live.values.sum()
            val bigMs = bigHours * 3_600_000L
            bigGoalText.text = "Цель $bigHours часов"
            bigGoalText.visibility = View.VISIBLE
            bigProgress.visibility = View.VISIBLE
            bigGoalDetails.visibility = View.VISIBLE
            bigProgress.setIndicatorColor(restColor)
            bigProgress.setProgressCompat((all * 1000 / bigMs).coerceAtMost(1000L).toInt(), false)
            val left = (bigMs - all).coerceAtLeast(0L)
            bigGoalDetails.text = if (left == 0L) "Пройдено: ${Fmt.hours(all)} ч. Цель достигнута!"
            else "Пройдено ${Fmt.hours(all)} ч · осталось ${Fmt.hours(left)} ч"
        } else {
            bigGoalText.visibility = View.GONE
            bigProgress.visibility = View.GONE
            bigGoalDetails.visibility = View.GONE
        }
    }

    // ---------------- проекты ----------------

    private fun chooseProject() {
        val projects = Projects.all(this)
        val cur = Projects.currentId(this)
        val items = projects.map { (if (it.id == cur) "✓  " else "     ") + it.name } +
            listOf("＋  Новый проект", "✎  Переименовать или удалить")
        MaterialAlertDialogBuilder(this)
            .setTitle("Проект")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < projects.size -> Engine.switchProject(this, projects[which].id)
                    which == projects.size -> askName("Новый проект", "") { name ->
                        val p = Projects.add(this, name)
                        Engine.switchProject(this, p.id)
                    }
                    else -> manageProjects()
                }
            }
            .show()
    }

    private fun manageProjects() {
        val projects = Projects.all(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("Какой проект изменить?")
            .setItems(projects.map { it.name }.toTypedArray()) { _, which ->
                val pr = projects[which]
                val actions = if (pr.id == Projects.MAIN) arrayOf("Переименовать")
                else arrayOf("Переименовать", "Удалить")
                MaterialAlertDialogBuilder(this)
                    .setTitle(pr.name)
                    .setItems(actions) { _, a ->
                        if (a == 0) {
                            askName("Новое название", pr.name) { name ->
                                Projects.rename(this, pr.id, name)
                                render()
                            }
                        } else {
                            confirmDelete(pr)
                        }
                    }
                    .show()
            }
            .show()
    }

    private fun confirmDelete(pr: Project) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить «${pr.name}»?")
            .setMessage("Проект исчезнет из списка. Его время останется в общей статистике.")
            .setPositiveButton("Удалить") { _, _ ->
                if (Projects.currentId(this) == pr.id) Engine.switchProject(this, Projects.MAIN)
                Projects.delete(this, pr.id)
                render()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSingleLine()
            setSelection(text.length)
            hint = "Название"
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    onOk(name)
                    render()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
