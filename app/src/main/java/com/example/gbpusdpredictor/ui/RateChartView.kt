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

    private val dark = Night.isOn(context)

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
        color = Color.parseColor(if (dark) "#5A6472" else "#9AA4B2")
        strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (dark) "#AAB2BF" else "#6B7280")
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

    // Only the most recent trading days are drawn: plotting everything since September on one time scale made the
    // forecast segment (1 day out of ~40) a stub and squashed day-to-day moves flat.
    private val historyWindow = 30
    // Share of the chart width reserved for the forecast, whatever the horizon, so even a 1-day forecast is clearly
    // visible. History is spaced by trading day inside the rest; forecasts are spaced by calendar day inside this zone.
    private val forecastFraction = 0.30f

    private val zonePaint = Paint().apply { color = Color.parseColor(if (dark) "#1D2430" else "#F1F5FB"); style = Paint.Style.FILL }
    private val refLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA4B2"); strokeWidth = 2f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(if (dark) "#252B36" else "#FFFFFF"); style = Paint.Style.FILL }
    private val boxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; isFakeBoldText = true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val hist = historical.takeLast(historyWindow)
        if (hist.size < 2) {
            canvas.drawText("Not enough data yet for a chart", width / 2f, height / 2f, emptyStatePaint)
            return
        }

        val paddingLeft = 100f
        val paddingRight = 16f
        val paddingTop = 20f
        val paddingBottom = 50f
        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingTop - paddingBottom
        val preds = predictedLines.sortedBy { it.point.date }
        val forecastW = if (preds.isEmpty()) 0f else chartWidth * forecastFraction
        val histW = chartWidth - forecastW
        val histEndX = paddingLeft + histW

        val allRates = hist.map { it.rate } + preds.map { it.point.rate }
        val minRate = allRates.min()
        val maxRate = allRates.max()
        val rateRange = (maxRate - minRate).let { if (it > 0.0001) it else 0.01 }
        val yPad = rateRange * 0.15

        val lastHist = hist.last()
        fun xForHist(i: Int): Float = paddingLeft + histW * i / (hist.size - 1)
        val maxAhead = max(1, preds.maxOfOrNull { ChronoUnit.DAYS.between(lastHist.date, it.point.date).toInt() } ?: 1)
        fun xForPred(date: LocalDate): Float =
            histEndX + forecastW * ChronoUnit.DAYS.between(lastHist.date, date).toFloat() / maxAhead
        fun yFor(rate: Double): Float {
            val t = (rate - (minRate - yPad)) / (rateRange + 2 * yPad)
            return paddingTop + chartHeight - (t * chartHeight).toFloat()
        }

        if (forecastW > 0f) {
            canvas.drawRect(histEndX, paddingTop, paddingLeft + chartWidth, paddingTop + chartHeight, zonePaint)
            canvas.drawText("Forecast", histEndX + 10f, paddingTop + 30f, labelPaint)
        }

        canvas.drawLine(paddingLeft, paddingTop, paddingLeft, paddingTop + chartHeight, axisPaint)
        canvas.drawLine(paddingLeft, paddingTop + chartHeight, paddingLeft + chartWidth, paddingTop + chartHeight, axisPaint)
        canvas.drawText("%.4f".format(Locale.UK, maxRate), 4f, paddingTop + 10f, labelPaint)
        canvas.drawText("%.4f".format(Locale.UK, minRate), 4f, paddingTop + chartHeight, labelPaint)

        // Today's rate carried across the forecast zone, so up or down is obvious at a glance.
        val lastY = yFor(lastHist.rate)
        canvas.drawLine(paddingLeft, lastY, paddingLeft + chartWidth, lastY, refLinePaint)

        val path = Path()
        hist.forEachIndexed { i, p -> if (i == 0) path.moveTo(xForHist(i), yFor(p.rate)) else path.lineTo(xForHist(i), yFor(p.rate)) }
        canvas.drawPath(path, historyLinePaint)

        val dateFmt = DateTimeFormatter.ofPattern("dd MMM")
        val baseY = paddingTop + chartHeight + 36f
        canvas.drawText(hist.first().date.format(dateFmt), paddingLeft, baseY, labelPaint)
        dotPaint.color = Color.parseColor("#4A90D9")
        canvas.drawCircle(histEndX, lastY, 10f, dotPaint)
        val lastLabel = lastHist.date.format(dateFmt)
        canvas.drawText(lastLabel, (histEndX - labelPaint.measureText(lastLabel) / 2).coerceAtLeast(paddingLeft), baseY, labelPaint)

        preds.forEachIndexed { index, pred ->
            val px = xForPred(pred.point.date)
            val py = yFor(pred.point.rate)
            val linePaint = Paint(predictedLinePaintTemplate).apply { color = pred.color; strokeWidth = 6f }
            canvas.drawLine(histEndX, lastY, px, py, linePaint)
            dotPaint.color = pred.color
            canvas.drawCircle(px, py, 13f, dotPaint)

            // White box with the value and its change, kept fully inside the view (it used to run off the right
            // edge as "1d (22 Oct): 1.3"). Several horizons stack downwards so they don't overlap.
            val pct = (pred.point.rate / lastHist.rate - 1) * 100
            val text = "${pred.label} ${pred.point.date.format(dateFmt)}  %.4f  %+.2f%%".format(Locale.UK, pred.point.rate, pct)
            valuePaint.color = pred.color
            val tw = valuePaint.measureText(text)
            val boxW = tw + 24f
            val boxH = 44f
            val left = (px - boxW / 2).coerceIn(paddingLeft + 4f, width - paddingRight - boxW)
            var top = py - boxH - 22f - index * (boxH + 6f)
            if (top < paddingTop) top = py + 22f + index * (boxH + 6f)
            boxStrokePaint.color = pred.color
            canvas.drawRoundRect(left, top, left + boxW, top + boxH, 12f, 12f, boxPaint)
            canvas.drawRoundRect(left, top, left + boxW, top + boxH, 12f, 12f, boxStrokePaint)
            canvas.drawText(text, left + 12f, top + boxH - 13f, valuePaint)
        }
        preds.lastOrNull()?.let {
            val l = it.point.date.format(dateFmt)
            canvas.drawText(l, width - paddingRight - labelPaint.measureText(l), baseY, labelPaint)
        }
    }
}
