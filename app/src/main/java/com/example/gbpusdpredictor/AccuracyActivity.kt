package com.example.gbpusdpredictor

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.gbpusdpredictor.data.AppDatabase
import com.example.gbpusdpredictor.repository.PredictionScorer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * "How accurate has this been?" -- scores every past stored forecast against what GBP/USD
 * actually did next (see PredictionScorer). Until this screen existed the app only ever
 * showed its NEXT call, which can't be checked yet; ml/README.md's own backtest puts the
 * model at ~55-57% directional in calm periods and below chance through regime breaks, so
 * being able to see the live track record matters more than any single forecast.
 *
 * Read-only, same as HistoryActivity/BootstrapLogActivity -- one getAllEntries() read,
 * everything else is pure arithmetic.
 */
class AccuracyActivity : AppCompatActivity() {

    private val columnWidthDp = 116

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(this).apply {
            text = "Forecast accuracy"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(24, 24, 24, 4)
        })

        val summaryView = TextView(this).apply {
            textSize = 13f
            setPadding(24, 0, 24, 8)
        }
        root.addView(summaryView)

        root.addView(TextView(this).apply {
            text = "Each row: the model's 1-business-day call made from “From”, scored against the " +
                "actual GBP/USD on “Target”. Direction = did predicted and actual move the same way. " +
                "Small samples and calm-market periods flatter this number; a genuine regime break is where " +
                "it has historically done worst."
            textSize = 11f
            setPadding(24, 0, 24, 16)
        })

        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(HorizontalScrollView(this).apply { addView(rows) })

        com.example.gbpusdpredictor.ui.ScreenHost.install(this, ScrollView(this).apply { addView(root) })

        CoroutineScope(Dispatchers.Main).launch {
            val (scored, summary) = withContext(Dispatchers.IO) {
                PredictionScorer.score(
                    AppDatabase.getInstance(applicationContext).marketDao().getAllEntries()
                )
            }

            summaryView.text = buildString {
                if (summary.scored == 0) {
                    append("No forecasts old enough to score yet")
                    if (summary.pending > 0) append(" (${summary.pending} waiting on their target date)")
                    append(".")
                } else {
                    val acc = summary.directionAccuracy ?: 0.0
                    append("Scored ${summary.scored} · ")
                    append("Direction correct ${summary.directionHits}/${summary.scored} ")
                    append("(${"%.0f".format(Locale.UK, acc * 100)}%) · ")
                    append("Mean error ${"%.4f".format(Locale.UK, summary.meanAbsErrorRate)} · ")
                    append("Median error ${"%.4f".format(Locale.UK, summary.medianAbsErrorRate)}")
                    if (summary.pending > 0) append(" · ${summary.pending} pending")
                }
            }

            if (scored.isEmpty()) return@launch

            addRow(
                rows,
                listOf("From", "Target", "Base", "Predicted", "Actual", "Pred Δ%", "Actual Δ%", "Direction"),
                bold = true,
                color = null
            )
            scored.forEach { s ->
                addRow(
                    rows,
                    listOf(
                        s.forecastDate.toString(),
                        s.matchedActualDate.toString(),
                        "%.4f".format(Locale.UK, s.baseRate),
                        "%.4f".format(Locale.UK, s.predictedRate),
                        "%.4f".format(Locale.UK, s.actualRate),
                        "%+.2f%%".format(Locale.UK, s.predictedChangePct * 100),
                        "%+.2f%%".format(Locale.UK, s.actualChangePct * 100),
                        if (s.directionHit) "✓ hit" else "✗ miss"
                    ),
                    bold = false,
                    color = if (s.directionHit) Color.parseColor("#12B886") else Color.parseColor("#E5484D")
                )
            }
        }
    }

    private fun addRow(parent: LinearLayout, cells: List<String>, bold: Boolean, color: Int?) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val widthPx = (columnWidthDp * resources.displayMetrics.density).toInt()
        cells.forEach { text ->
            row.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                gravity = Gravity.START
                setPadding(16, 12, 16, 12)
                layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                if (color != null) setTextColor(color)
            })
        }
        parent.addView(row)
    }
}
