package com.example.gbpusdpredictor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.time.LocalDate
import java.util.Locale
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * Minimal, dependency-free line chart: historical GBP/USD rate plus the model's
 * predicted point out at the forecast horizon. Drawn directly with Canvas rather than
 * a charting library -- given how much of this project turned into fighting third-party
 * dependency versions, a ~150-line self-contained View is the safer choice here than
 * one more Gradle dependency that could conflict with something else down the line.
 */
class RateChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Point(val date: LocalDate, val rate: Double)

    /** One forecast line: a horizon's predicted point, the colour its day-button uses
     * (so the line and the button that toggled it are visually tied together), and a
     * short label ("1d", "7d", etc.) drawn next to its dot. */
    data class PredictedLine(val point: Point, val color: Int, val label: String)

    private var historical: List<Point> = emptyList()
    private var predictedLines: List<PredictedLine> = emptyList()

    private val historyLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A90D9")
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val predictedLinePaintTemplate = Paint(historyLinePaint).apply {
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA4B2")
        strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textSize = 26f
    }
    private val emptyStatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA4B2")
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }

    /** @param historical chronological (oldest first). @param predictedLines zero or more
     * forecast points to draw -- empty if no forecast is available, one for a single
     * selected horizon, or several (each its own colour/dashed line) when comparing
     * multiple horizons at once. */
    fun setData(historical: List<Point>, predictedLines: List<PredictedLine>) {
        this.historical = historical
        this.predictedLines = predictedLines
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (historical.size < 2) {
            canvas.drawText("Not enough data yet for a chart", width / 2f, height / 2f, emptyStatePaint)
            return
        }

        val paddingLeft = 100f
        val paddingRight = 20f
        val paddingTop = 20f
        val paddingBottom = 50f
        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingTop - paddingBottom

        val allPoints = historical + predictedLines.map { it.point }
        val minRate = allPoints.minOf { it.rate }
        val maxRate = allPoints.maxOf { it.rate }
        val rateRange = (maxRate - minRate).let { if (it > 0.0001) it else 0.01 }
        val yPad = rateRange * 0.15

        val firstDate = historical.first().date
        val lastDate = predictedLines.maxOfOrNull { it.point.date } ?: historical.last().date
        val totalDays = max(1, ChronoUnit.DAYS.between(firstDate, lastDate).toInt())

        fun xFor(date: LocalDate): Float {
            val d = ChronoUnit.DAYS.between(firstDate, date).toFloat()
            return paddingLeft + (d / totalDays) * chartWidth
        }
        fun yFor(rate: Double): Float {
            val t = (rate - (minRate - yPad)) / (rateRange + 2 * yPad)
            return paddingTop + chartHeight - (t * chartHeight).toFloat()
        }

        canvas.drawLine(paddingLeft, paddingTop, paddingLeft, paddingTop + chartHeight, axisPaint)
        canvas.drawLine(paddingLeft, paddingTop + chartHeight, paddingLeft + chartWidth, paddingTop + chartHeight, axisPaint)

        canvas.drawText("%.4f".format(Locale.UK, maxRate), 4f, paddingTop + 10f, labelPaint)
        canvas.drawText("%.4f".format(Locale.UK, minRate), 4f, paddingTop + chartHeight, labelPaint)

        val path = Path()
        historical.forEachIndexed { i, p ->
            val x = xFor(p.date)
            val y = yFor(p.rate)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, historyLinePaint)

        val dateFmt = DateTimeFormatter.ofPattern("dd MMM")
        canvas.drawText(firstDate.format(dateFmt), paddingLeft, paddingTop + chartHeight + 36f, labelPaint)

        val lastHist = historical.last()
        dotPaint.color = Color.parseColor("#4A90D9")
        canvas.drawCircle(xFor(lastHist.date), yFor(lastHist.rate), 10f, dotPaint)
        canvas.drawText(
            lastHist.date.format(dateFmt),
            (xFor(lastHist.date) - 60f).coerceAtLeast(paddingLeft),
            paddingTop + chartHeight + 36f,
            labelPaint
        )

        // Sorted by horizon (nearest first) so labels stack top-to-bottom in a sensible
        // order when several lines land close together on screen, rather than whatever
        // order the caller happened to build the list in.
        predictedLines.sortedBy { it.point.date }.forEachIndexed { index, pred ->
            val linePaint = Paint(predictedLinePaintTemplate).apply { color = pred.color }
            canvas.drawLine(xFor(lastHist.date), yFor(lastHist.rate), xFor(pred.point.date), yFor(pred.point.rate), linePaint)

            dotPaint.color = pred.color
            canvas.drawCircle(xFor(pred.point.date), yFor(pred.point.rate), 10f, dotPaint)

            // Vertically offset each label by its index so multiple close-together
            // predictions (e.g. 1d-7d all comparable rates) don't draw directly on top
            // of each other and become unreadable.
            val labelYBase = (yFor(pred.point.rate) - 16f).coerceAtLeast(paddingTop + 20f)
            val labelY = labelYBase - (index * 30f)
            val labelPaintColored = Paint(labelPaint).apply { color = pred.color }
            canvas.drawText(
                "${pred.label} (${pred.point.date.format(dateFmt)}): %.4f".format(Locale.UK, pred.point.rate),
                (xFor(pred.point.date) - 160f).coerceAtLeast(paddingLeft),
                labelY,
                labelPaintColored
            )
        }
    }
}
