package com.worktimer.pomo

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class StatsActivity : AppCompatActivity() {

    private enum class Mode { DAYS, WEEKS, MONTHS, YEARS }

    private var mode = Mode.DAYS
    private var filter: String? = null // null — все проекты

    private lateinit var chart: BarChartView
    private lateinit var summaryText: TextView
    private lateinit var breakdownText: TextView
    private lateinit var totalText: TextView
    private lateinit var filterButton: MaterialButton

    private val months = arrayOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
    private val ddmm: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        chart = findViewById(R.id.chart)
        summaryText = findViewById(R.id.summaryText)
        breakdownText = findViewById(R.id.breakdownText)
        totalText = findViewById(R.id.totalText)
        filterButton = findViewById(R.id.filterButton)

        findViewById<MaterialButton>(R.id.backButton).setOnClickListener { finish() }

        val group = findViewById<MaterialButtonToggleGroup>(R.id.modeGroup)
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            mode = when (id) {
                R.id.modeWeeks -> Mode.WEEKS
                R.id.modeMonths -> Mode.MONTHS
                R.id.modeYears -> Mode.YEARS
                else -> Mode.DAYS
            }
            render()
        }
        group.check(R.id.modeDays)

        filterButton.setOnClickListener {
            val projects = Projects.all(this)
            val names = listOf("Все проекты") + projects.map { it.name }
            MaterialAlertDialogBuilder(this)
                .setTitle("Показать")
                .setItems(names.toTypedArray()) { _, which ->
                    filter = if (which == 0) null else projects[which - 1].id
                    render()
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private class Period(val label: String, val from: LocalDate, val to: LocalDate, val current: Boolean)

    private fun periods(today: LocalDate): List<Period> = when (mode) {
        Mode.DAYS -> (13 downTo 0).map {
            val d = today.minusDays(it.toLong())
            Period(d.dayOfMonth.toString(), d, d, it == 0)
        }
        Mode.WEEKS -> (11 downTo 0).map {
            val mon = today.with(DayOfWeek.MONDAY).minusWeeks(it.toLong())
            Period(mon.format(ddmm), mon, mon.plusDays(6), it == 0)
        }
        Mode.MONTHS -> (11 downTo 0).map {
            val m = today.withDayOfMonth(1).minusMonths(it.toLong())
            Period(months[m.monthValue - 1], m, m.plusMonths(1).minusDays(1), it == 0)
        }
        Mode.YEARS -> (4 downTo 0).map {
            val y = today.withDayOfYear(1).minusYears(it.toLong())
            Period(y.year.toString(), y, y.plusYears(1).minusDays(1), it == 0)
        }
    }

    private fun render() {
        val work = Stats.loadWorkLive(this)
        val pomos = Stats.loadPomodoros(this)
        val projects = Projects.all(this)
        val known = projects.associateBy { it.id }
        val today = LocalDate.now()
        val list = periods(today)

        filterButton.text = (filter?.let { known[it]?.name } ?: "Все проекты") + " ▾"

        fun inFilter(p: String) = filter == null || filter == p

        // суммы по проектам для каждого периода
        val perPeriod = list.map { per ->
            val sums = linkedMapOf<String, Long>()
            for ((day, m) in work) {
                if (day < per.from || day > per.to) continue
                for ((p, ms) in m) if (inFilter(p)) sums[p] = (sums[p] ?: 0L) + ms
            }
            sums
        }

        val bars = list.mapIndexed { i, per ->
            val sums = perPeriod[i]
            val parts = mutableListOf<Pair<Int, Long>>()
            for (pr in projects) sums[pr.id]?.let { parts += Projects.colorOf(pr) to it }
            val deleted = sums.filterKeys { it !in known }.values.sum()
            if (deleted > 0) parts += Projects.DELETED_COLOR to deleted
            BarChartView.Bar(per.label, parts, per.current)
        }
        chart.setBars(bars)

        // сводка за видимый промежуток
        val first = list.first().from
        val last = list.last().to
        val total = bars.sumOf { it.total }
        val active = bars.count { it.total > 0 }
        var pomoCount = 0
        for ((day, m) in pomos) {
            if (day < first || day > last) continue
            for ((p, n) in m) if (inFilter(p)) pomoCount += n
        }
        val (span, unit) = when (mode) {
            Mode.DAYS -> "14 дней" to "в день"
            Mode.WEEKS -> "12 недель" to "в неделю"
            Mode.MONTHS -> "12 месяцев" to "в месяц"
            Mode.YEARS -> "5 лет" to "в год"
        }
        val current = bars.last().total
        val currentName = when (mode) {
            Mode.DAYS -> "Сегодня"
            Mode.WEEKS -> "Эта неделя"
            Mode.MONTHS -> "Этот месяц"
            Mode.YEARS -> "Этот год"
        }
        summaryText.text = buildString {
            append("$currentName: ${Fmt.duration(current)}")
            append("\nЗа $span: ${Fmt.duration(total)} · помидоров: $pomoCount")
            if (active > 0) append("\nВ среднем ${Fmt.duration(total / active)} $unit с работой")
        }

        // по проектам за видимый промежуток
        val byProject = linkedMapOf<String, Long>()
        perPeriod.forEach { m -> m.forEach { (p, ms) -> byProject[p] = (byProject[p] ?: 0L) + ms } }
        val sb = SpannableStringBuilder()
        if (byProject.isEmpty()) {
            sb.append("Пока нет записанного времени.")
        } else {
            byProject.entries.sortedByDescending { it.value }.forEachIndexed { i, (p, ms) ->
                if (i > 0) sb.append("\n")
                val pr = known[p]
                val color = pr?.let { Projects.colorOf(it) } ?: Projects.DELETED_COLOR
                val start = sb.length
                sb.append("●  ")
                sb.setSpan(ForegroundColorSpan(color), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val pct = if (total > 0) (ms * 100 / total) else 0
                sb.append("${pr?.name ?: "Удалённые проекты"} — ${Fmt.duration(ms)} ($pct%)")
            }
        }
        breakdownText.text = sb

        // за всё время
        var all = 0L
        var allPomo = 0
        work.values.forEach { m -> m.forEach { (p, ms) -> if (inFilter(p)) all += ms } }
        pomos.values.forEach { m -> m.forEach { (p, n) -> if (inFilter(p)) allPomo += n } }
        totalText.text = "За всё время: ${Fmt.duration(all)} (${Fmt.hours(all)} ч) · помидоров: $allPomo"
    }
}
