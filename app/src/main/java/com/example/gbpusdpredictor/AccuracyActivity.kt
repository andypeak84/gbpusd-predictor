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

        // Latest GBP/USD (ECB reference rate via Frankfurter, same source as the forecast) so the table
        // below can be read against where the pound is NOW, not just where each forecast was scored.
        val currentView = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(24, 0, 24, 8)
            text = "Latest GBP/USD: checking..."
        }
        root.addView(currentView)

        val summaryView = TextView(this).apply {
            textSize = 13f
            setPadding(24, 0, 24, 8)
        }
        root.addView(summaryView)

        val newsView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#1C64C8"))
            setPadding(24, 0, 24, 8)
        }
        root.addView(newsView)

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
            val entries = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(applicationContext).marketDao().getAllEntries()
            }
            // Preferred: the hourly Alpha Vantage reading the price-alert worker already stores on this phone
            // (the user's own key, so no extra API call from this screen). Used only while it is fresh;
            // otherwise the ECB reference rate (same source as the forecast) so there is always a number.
            val hourly = with(com.example.gbpusdpredictor.work.PriceAlertPrefs) {
                val rate = lastHourlyRate
                val at = runCatching { java.time.Instant.parse(lastHourlyRateUtc) }.getOrNull()
                if (rate != null && at != null && java.time.Duration.between(at, java.time.Instant.now()).toHours() < 3) rate to at else null
            }
            val ecb = if (hourly == null) withContext(Dispatchers.IO) {
                runCatching { com.example.gbpusdpredictor.network.FrankfurterApi.service.getLatest() }.getOrNull()
            } else null
            val ecbRate = ecb?.rates?.get("USD")
            val rate: Double? = hourly?.first ?: ecbRate
            val lastBase = entries.filter { it.gbpUsd != null }.maxByOrNull { it.date }
            currentView.text = if (rate == null) {
                "Latest GBP/USD: unavailable (offline?)"
            } else buildString {
                val source = if (hourly != null) {
                    "Alpha Vantage, " + java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm 'UTC'")
                        .withZone(java.time.ZoneOffset.UTC).format(hourly.second)
                } else "ECB rate, ${ecb?.date}"
                append("Latest GBP/USD: ${"%.4f".format(Locale.UK, rate)} ($source)")
                if (lastBase?.gbpUsd != null) {
                    val pct = (rate / lastBase.gbpUsd - 1) * 100
                    append("\n${"%+.2f%%".format(Locale.UK, pct)} since the newest stored row (${lastBase.date}, ${"%.4f".format(Locale.UK, lastBase.gbpUsd)})")
                }
            }

            val (scored, summary) = withContext(Dispatchers.Default) { PredictionScorer.score(entries) }

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

            // Second scorer: the same forecasts WITH the news-sentiment overlay, as the main screen applies it
            // (SentimentAdjustment.tilt with the user's current weight / full-strength settings). Each forecast uses
            // the last published reading from BEFORE its target day's ECB fix (~13:15 UTC) and no older than
            // MAX_AGE_HOURS, so no news from after the outcome is ever used. Forecasts with no reading in that window
            // (the sentiment history only starts 21 Oct 2026) are left out of this score, not counted as misses.
            val history = withContext(Dispatchers.IO) {
                runCatching {
                    val arr = org.json.JSONArray(com.example.gbpusdpredictor.network.SentimentApi.service.getHistoryRaw())
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.getJSONObject(i)
                        if (!o.has("ts") || !o.has("s24h")) null
                        else Triple(o.getLong("ts"), o.getDouble("s24h"), o.optInt("n24h", 0))
                    }.sortedBy { it.first }
                }.getOrDefault(emptyList())
            }
            val sd1 = withContext(Dispatchers.Default) {
                com.example.gbpusdpredictor.ml.TreeModelPredictor(applicationContext).horizonSdPct(1)
            }
            val weight = com.example.gbpusdpredictor.sentiment.SentimentRepository.weight(this@AccuracyActivity)
            val fullStrength = com.example.gbpusdpredictor.sentiment.SentimentRepository.fullStrengthStories(this@AccuracyActivity)
            val maxAgeS = (com.example.gbpusdpredictor.sentiment.SentimentAdjustment.MAX_AGE_HOURS * 3600).toLong()
            val newsTilt: Map<java.time.LocalDate, Double> = scored.mapNotNull { s ->
                val cutoff = s.matchedActualDate.atTime(13, 15).toEpochSecond(java.time.ZoneOffset.UTC)
                val reading = history.lastOrNull { it.first < cutoff } ?: return@mapNotNull null
                if (cutoff - reading.first > maxAgeS) return@mapNotNull null
                s.forecastDate to com.example.gbpusdpredictor.sentiment.SentimentAdjustment.tilt(
                    reading.second, reading.third, sd1, weight, fullStrength)
            }.toMap()
            val withNews = scored.filter { it.forecastDate in newsTilt }
            fun newsHit(s: PredictionScorer.ScoredForecast) =
                ((s.predictedChangePct + newsTilt.getValue(s.forecastDate)) >= 0.0) == (s.actualChangePct >= 0.0)
            newsView.text = if (withNews.isEmpty()) {
                "With news sentiment: no forecasts yet with a news reading from before their target day (history began 21 Oct 2026)."
            } else {
                val hitsNews = withNews.count { newsHit(it) }
                val hitsModel = withNews.count { it.directionHit }
                "With news sentiment: direction correct $hitsNews/${withNews.size} " +
                    "(${"%.0f".format(Locale.UK, 100.0 * hitsNews / withNews.size)}%) · model alone on the same " +
                    "${withNews.size}: $hitsModel/${withNews.size}. Uses your current news weight (${"%.2f".format(Locale.UK, weight)})."
            }

            addRow(
                rows,
                listOf("From", "Target", "Base", "Predicted", "Actual", "Pred Δ%", "Actual Δ%", "Direction", "News tilt", "With news"),
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
                        if (s.directionHit) "✓ hit" else "✗ miss",
                        newsTilt[s.forecastDate]?.let { "%+.2f%%".format(Locale.UK, it * 100) } ?: "—",
                        if (s.forecastDate !in newsTilt) "—" else if (newsHit(s)) "✓ hit" else "✗ miss"
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
