package com.worktimer.pomo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Простой столбчатый график; столбец может состоять из нескольких цветных частей (проекты). */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    class Bar(val label: String, val parts: List<Pair<Int, Long>>, val highlight: Boolean = false) {
        val total: Long get() = parts.sumOf { it.second }
    }

    private var bars: List<Bar> = emptyList()
    private val dp = resources.displayMetrics.density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp
        color = ContextCompat.getColor(context, R.color.chart_grid)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        color = ContextCompat.getColor(context, R.color.chart_text)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
        color = ContextCompat.getColor(context, R.color.chart_text)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setBars(b: List<Bar>) {
        bars = b
        invalidate()
    }

    private fun niceStep(maxV: Long): Long {
        val stepsMin = longArrayOf(5, 10, 15, 30, 60, 120, 180, 300, 600, 1200, 3000, 6000, 12000, 30000, 60000)
        for (m in stepsMin) {
            val ms = m * 60_000L
            if (maxV / ms <= 4) return ms
        }
        return stepsMin.last() * 60_000L
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return

        val left = 44 * dp
        val right = width - 4 * dp
        val top = 16 * dp
        val bottom = height - 20 * dp
        val chartH = bottom - top

        val maxV = bars.maxOf { it.total }
        val step = niceStep(maxV)
        val steps = max(1L, ceil(maxV.toDouble() / step).toLong())
        val maxAxis = (steps * step).toFloat()

        // сетка и подписи оси
        textPaint.textAlign = Paint.Align.RIGHT
        var v = 0L
        while (v <= steps * step) {
            val y = bottom - chartH * (v / maxAxis)
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText(Fmt.axis(v), left - 6 * dp, y + 3.5f * dp, textPaint)
            v += step
        }

        val slot = (right - left) / bars.size
        val bw = min(slot * 0.62f, 30 * dp)
        textPaint.textAlign = Paint.Align.CENTER
        val maxLabelW = bars.maxOf { textPaint.measureText(it.label) }
        val every = max(1, ceil((maxLabelW + 6 * dp) / slot).toInt())
        val showValues = bars.size <= 14 && slot > 26 * dp

        bars.forEachIndexed { i, b ->
            val cx = left + slot * (i + 0.5f)
            var yCur = bottom
            for ((color, ms) in b.parts) {
                if (ms <= 0L) continue
                val h = chartH * (ms / maxAxis)
                barPaint.color = color
                barPaint.alpha = if (b.highlight || bars.none { it.highlight }) 255 else 200
                rect.set(cx - bw / 2, yCur - h, cx + bw / 2, yCur)
                canvas.drawRoundRect(rect, 3 * dp, 3 * dp, barPaint)
                canvas.drawRect(cx - bw / 2, yCur - min(h, 3 * dp), cx + bw / 2, yCur, barPaint)
                yCur -= h
            }
            if (showValues && b.total > 0L) {
                canvas.drawText(Fmt.hours(b.total), cx, yCur - 4 * dp, valuePaint)
            }
            if ((bars.size - 1 - i) % every == 0) {
                textPaint.isFakeBoldText = b.highlight
                canvas.drawText(b.label, cx, bottom + 14 * dp, textPaint)
                textPaint.isFakeBoldText = false
            }
        }
    }
}
