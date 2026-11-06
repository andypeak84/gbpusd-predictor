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
            setTextColor(com.example.gbpusdpredictor.ui.Night.accentBlue(this@AccuracyActivity))
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
            // The shared live-rate store (work/LiveRate): the same reading the main screen and the price alerts use, so opening
            // this screen costs no extra Alpha Vantage call. If no live source answers, the ECB daily rate so there is a number.
            val live = withContext(Dispatchers.IO) { runCatching { com.example.gbpusdpredictor.work.LiveRate.current(applicationContext) }.getOrNull() }
            val ecb = if (live == null) withContext(Dispatchers.IO) {
                runCatching { com.example.gbpusdpredictor.network.FrankfurterApi.service.getLatest() }.getOrNull()
            } else null
            val ecbRate = ecb?.rates?.get("USD")
            val rate: Double? = live?.rate ?: ecbRate
            val lastBase = entries.filter { it.gbpUsd != null }.maxByOrNull { it.date }
            currentView.text = if (rate == null) {
                "Latest GBP/USD: unavailable (offline?)"
            } else buildString {
                val source = if (live != null) {
                    live.source.label + ", " + java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm 'UTC'")
                        .withZone(java.time.ZoneOffset.UTC).format(live.at)
                } else "ECB daily rate, ${ecb?.date}"
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
            val treeModel = withContext(Dispatchers.Default) { com.example.gbpusdpredictor.ml.TreeModelPredictor(applicationContext) }
            val sd1 = treeModel.horizonSdPct(1)
            val weight = com.example.gbpusdpredictor.sentiment.SentimentRepository.weight(this@AccuracyActivity)
            val fullStrength = com.example.gbpusdpredictor.sentiment.SentimentRepository.fullStrengthStories(this@AccuracyActivity)
            val maxAgeS = (com.example.gbpusdpredictor.sentiment.SentimentAdjustment.MAX_AGE_HOURS * 3600).toLong()
            // The news reading each forecast would have used (score, story count), found once; the tilt itself is then
            // recomputed for any weight / full-strength pair, so the sweet-spot scan below can try them all.
            val readings: Map<java.time.LocalDate, Pair<Double, Int>> = scored.mapNotNull { s ->
                val cutoff = s.matchedActualDate.atTime(13, 15).toEpochSecond(java.time.ZoneOffset.UTC)
                val reading = history.lastOrNull { it.first < cutoff } ?: return@mapNotNull null
                if (cutoff - reading.first > maxAgeS) return@mapNotNull null
                s.forecastDate to (reading.second to reading.third)
            }.toMap()
            fun tiltsFor(w: Double, fs: Int) = readings.mapValues { (_, r) ->
                com.example.gbpusdpredictor.sentiment.SentimentAdjustment.tilt(r.first, r.second, sd1, w, fs)
            }
            val newsTilt = tiltsFor(weight, fullStrength)
            val withNews = scored.filter { it.forecastDate in newsTilt }
            fun hitWith(s: PredictionScorer.ScoredForecast, tilt: Double) =
                ((s.predictedChangePct + tilt) >= 0.0) == (s.actualChangePct >= 0.0)
            fun newsHit(s: PredictionScorer.ScoredForecast) = hitWith(s, newsTilt.getValue(s.forecastDate))
            // News effect monitor: did the tilt move the forecast % TOWARDS the actual % (helped) or away (hurt)?
            fun effect(s: PredictionScorer.ScoredForecast): Int {
                val tilt = newsTilt[s.forecastDate] ?: return 0
                if (kotlin.math.abs(tilt) < 0.00005) return 0
                val before = kotlin.math.abs(s.predictedChangePct - s.actualChangePct)
                val after = kotlin.math.abs(s.predictedChangePct + tilt - s.actualChangePct)
                return if (after < before) 1 else -1
            }
            newsView.text = if (withNews.isEmpty()) {
                "With news sentiment: no forecasts yet with a news reading from before their target day (history began 21 Oct 2026)."
            } else {
                val n = withNews.size
                val hitsNews = withNews.count { newsHit(it) }
                val hitsModel = withNews.count { it.directionHit }
                val helped = withNews.count { effect(it) > 0 }
                val hurt = withNews.count { effect(it) < 0 }
                val avgTilt = withNews.sumOf { kotlin.math.abs(newsTilt.getValue(it.forecastDate)) } / n
                val errModel = withNews.sumOf { kotlin.math.abs(it.predictedChangePct - it.actualChangePct) } / n
                val errNews = withNews.sumOf {
                    kotlin.math.abs(it.predictedChangePct + newsTilt.getValue(it.forecastDate) - it.actualChangePct)
                } / n
                fun pct(x: Double) = "%.3f%%".format(Locale.UK, x * 100)
                "With news sentiment: direction correct $hitsNews/$n " +
                    "(${"%.0f".format(Locale.UK, 100.0 * hitsNews / n)}%) · model alone on the same $n: $hitsModel/$n. " +
                    "Uses your current news weight (${"%.2f".format(Locale.UK, weight)}, full strength at $fullStrength stories).\n" +
                    "News effect: average size ±${pct(avgTilt)} · moved the forecast towards the actual move $helped time(s), " +
                    "away $hurt time(s) · average % error ${pct(errModel)} model alone vs ${pct(errNews)} with news."
            }

            // Every horizon the main screen offers (1-7 business days), scored the same way once its target day has passed. Only the
            // 1-day call is stored, so the others are the current model re-run on each past day's stored inputs (PredictionScorer.score).
            // The news column uses the news reading from the morning after the row's fix (13:15 UTC, as for the 1-day score above),
            // scaled by that horizon's own typical move, exactly as the main screen scales it.
            val horizonLines = withContext(Dispatchers.Default) {
                (1..7).map { h ->
                    val (sc, sm) = if (h == 1) PredictionScorer.score(entries)
                    else PredictionScorer.score(entries, h) { r ->
                        treeModel.predictChangePct(com.example.gbpusdpredictor.repository.MarketRepository.featuresFrom(r, h))
                    }
                    if (sc.isEmpty()) return@map "$h day${if (h > 1) "s" else ""}: none scored yet" +
                        if (sm.pending > 0) " (${sm.pending} waiting on their target day)" else ""
                    val sdH = treeModel.horizonSdPct(h)
                    val newsHits = sc.mapNotNull { s ->
                        if (sdH == null) return@mapNotNull null
                        val cutoff = com.example.gbpusdpredictor.utils.AppClock.businessDaysForward(s.forecastDate, 1)
                            .atTime(13, 15).toEpochSecond(java.time.ZoneOffset.UTC)
                        val reading = history.lastOrNull { it.first < cutoff }?.takeIf { cutoff - it.first <= maxAgeS }
                            ?: return@mapNotNull null
                        val tilt = com.example.gbpusdpredictor.sentiment.SentimentAdjustment.tilt(reading.second, reading.third, sdH, weight, fullStrength, horizonDays = h)
                        hitWith(s, tilt) to s.directionHit
                    }
                    val acc = "%.0f".format(Locale.UK, 100.0 * sm.directionHits / sm.scored)
                    "$h day${if (h > 1) "s" else ""}: right direction ${sm.directionHits}/${sm.scored} ($acc%)" +
                        " · mean error ${"%.4f".format(Locale.UK, sm.meanAbsErrorRate)}" +
                        (if (newsHits.isNotEmpty()) " · with news ${newsHits.count { it.first }}/${newsHits.size} vs model alone ${newsHits.count { it.second }}/${newsHits.size}" else "") +
                        if (sm.pending > 0) " · ${sm.pending} waiting" else ""
                }
            }
            root.addView(TextView(this@AccuracyActivity).apply {
                textSize = 13f
                setPadding(24, 0, 24, 8)
                text = "By forecast horizon (each forecast scored once its target day has passed):\n" + horizonLines.joinToString("\n") +
                    "\nOnly the 1-day call is stored when it is made. The other horizons are today's model re-run on each day's saved inputs, " +
                    "so they show how the model does at each length, not what an earlier screen displayed."
            }, 4)

            // Formula comparison: the same forecasts (those with a news reading) scored under several ways of combining model and news, so
            // a better formula shows itself as days accumulate instead of being guessed from a handful. Direction right out of n, per horizon.
            // Nothing here changes the live forecast. Variants: the model alone; the formula as the main screen applies it; the same with
            // half the weight; the news fading with the horizon (SentimentAdjustment.fadeForHorizon); and news counted only when the model's
            // own call is near flat (inside the advice cutoff), the model alone otherwise.
            val formulaLines = withContext(Dispatchers.Default) {
                data class Row(val h: Int, val s: PredictionScorer.ScoredForecast, val tilt: Double)
                val perH = (1..7).associateWith { h ->
                    val sc = if (h == 1) PredictionScorer.score(entries).first
                    else PredictionScorer.score(entries, h) { r ->
                        treeModel.predictChangePct(com.example.gbpusdpredictor.repository.MarketRepository.featuresFrom(r, h))
                    }.first
                    val sdH = treeModel.horizonSdPct(h)
                    sc.mapNotNull { s ->
                        if (sdH == null) return@mapNotNull null
                        val cutoff = com.example.gbpusdpredictor.utils.AppClock.businessDaysForward(s.forecastDate, 1)
                            .atTime(13, 15).toEpochSecond(java.time.ZoneOffset.UTC)
                        val reading = history.lastOrNull { it.first < cutoff }?.takeIf { cutoff - it.first <= maxAgeS } ?: return@mapNotNull null
                        Row(h, s, com.example.gbpusdpredictor.sentiment.SentimentAdjustment.tilt(reading.second, reading.third, sdH, weight, fullStrength))
                    }
                }
                val variants: List<Pair<String, (Row) -> Double>> = listOf(
                    "Model alone" to { _ -> 0.0 },
                    // r.tilt is the shift WITHOUT the fade; the live formula (since v97) multiplies it by fadeForHorizon.
                    "Live formula (news fades with the horizon)" to { r -> r.tilt * com.example.gbpusdpredictor.sentiment.SentimentAdjustment.fadeForHorizon(r.h) },
                    "Same without the fade (before v97)" to { r -> r.tilt },
                    "Half the news weight, with the fade" to { r -> r.tilt * 0.5 * com.example.gbpusdpredictor.sentiment.SentimentAdjustment.fadeForHorizon(r.h) },
                    "News only when the model is near flat, with the fade" to { r ->
                        val flat = kotlin.math.abs(r.s.predictedChangePct) < (treeModel.adviceCutoffPctFor(r.h) ?: 0.0015)
                        if (flat) r.tilt * com.example.gbpusdpredictor.sentiment.SentimentAdjustment.fadeForHorizon(r.h) else 0.0
                    }
                )
                variants.map { (name, tiltOf) ->
                    val cells = (1..7).map { h ->
                        val rs = perH.getValue(h)
                        "${h}d ${rs.count { hitWith(it.s, tiltOf(it)) }}/${rs.size}"
                    }
                    val oneDay = perH.getValue(1)
                    val err = if (oneDay.isEmpty()) "" else
                        "  (1-day average error ${"%.3f".format(Locale.UK, 100 * oneDay.sumOf { kotlin.math.abs(it.s.predictedChangePct + tiltOf(it) - it.s.actualChangePct) } / oneDay.size)}%)"
                    "$name: ${cells.joinToString(" · ")}$err"
                }
            }
            root.addView(TextView(this@AccuracyActivity).apply {
                textSize = 13f
                setPadding(24, 0, 24, 8)
                text = "Formula comparison (right direction out of the forecasts that had a news reading):\n" + formulaLines.joinToString("\n") +
                    "\nA version is only worth switching to after about 30 scored days; with this few, differences of a forecast or two are luck."
            }, 5)

            // Sweet spot: try every weight (0 to the slider's max in 0.05 steps) x full-strength story count on the same
            // forecasts and rank by direction hits, then by smaller average % error. Weight 0 = the model alone.
            if (withNews.isNotEmpty()) {
                data class Trial(val w: Double, val fs: Int, val hits: Int, val err: Double)
                fun trial(w: Double, fs: Int): Trial {
                    val t = tiltsFor(w, fs)
                    return Trial(w, fs, withNews.count { hitWith(it, t.getValue(it.forecastDate)) },
                        withNews.sumOf { kotlin.math.abs(it.predictedChangePct + t.getValue(it.forecastDate) - it.actualChangePct) } / withNews.size)
                }
                val maxW = com.example.gbpusdpredictor.sentiment.SentimentAdjustment.MAX_WEIGHT_OF_HORIZON_SD
                val trials = withContext(Dispatchers.Default) {
                    (0..(maxW / 0.05).toInt()).flatMap { i ->
                        (1..com.example.gbpusdpredictor.sentiment.SentimentAdjustment.MAX_FULL_STRENGTH_STORIES).map { fs -> trial(i * 5 / 100.0, fs) }
                    }
                }
                val best = trials.sortedWith(compareByDescending<Trial> { it.hits }.thenBy { it.err }).first()
                val mine = trial(weight, fullStrength)
                val n = withNews.size
                fun line(t: Trial) = "weight ${"%.2f".format(Locale.UK, t.w)}, full strength at ${t.fs} stories: " +
                    "${t.hits}/$n hits, average error ${"%.3f".format(Locale.UK, t.err * 100)}%"
                root.addView(TextView(this@AccuracyActivity).apply {
                    textSize = 13f
                    setPadding(24, 0, 24, 4)
                    text = "News sweet spot (tested on $n forecast(s)):\nBest: ${line(best)}\nYours: ${line(mine)}" +
                        if (n < 20) "\nSmall sample -- treat this as a hint, not a setting to trust yet. It firms up as more days are scored." else ""
                }, 4)
                if (kotlin.math.abs(best.w - weight) > 0.001 || best.fs != fullStrength) {
                    root.addView(android.widget.Button(this@AccuracyActivity).apply {
                        text = "Use best news settings"
                        setOnClickListener {
                            com.example.gbpusdpredictor.sentiment.SentimentRepository.setWeight(this@AccuracyActivity, best.w)
                            com.example.gbpusdpredictor.sentiment.SentimentRepository.setFullStrengthStories(this@AccuracyActivity, best.fs)
                            android.widget.Toast.makeText(this@AccuracyActivity, "News settings updated", android.widget.Toast.LENGTH_SHORT).show()
                            recreate()
                        }
                    }, 5)
                }
            }

            addRow(
                rows,
                listOf("From", "Target", "Base", "Predicted", "Actual", "Pred Δ%", "Actual Δ%", "Direction", "News tilt", "With news", "News effect"),
                bold = true,
                color = null
            )
            // Forecasts already made whose target day has no actual rate yet (e.g. today's ECB rate before ~mid-afternoon):
            // listed first so the newest call is visible, and scored once its day arrives.
            summary.pendingForecasts.forEach { p ->
                addRow(
                    rows,
                    listOf(
                        p.forecastDate.toString(),
                        p.targetDate.toString(),
                        "%.4f".format(Locale.UK, p.baseRate),
                        "%.4f".format(Locale.UK, p.predictedRate),
                        "waiting",
                        "%+.2f%%".format(Locale.UK, p.predictedChangePct * 100),
                        "—", "⏳ waiting", "—", "—", "—"
                    ),
                    bold = false,
                    color = Color.GRAY
                )
            }
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
                        if (s.forecastDate !in newsTilt) "—" else if (newsHit(s)) "✓ hit" else "✗ miss",
                        when {
                            s.forecastDate !in newsTilt -> "—"
                            effect(s) > 0 -> "▲ helped"
                            effect(s) < 0 -> "▼ hurt"
                            else -> "· none"
                        }
                    ),
                    bold = false,
                    color = if (s.directionHit) Color.parseColor("#12B886") else Color.parseColor("#E5484D"),
                    cellColors = if (s.forecastDate !in newsTilt) mapOf(8 to Color.GRAY, 9 to Color.GRAY, 10 to Color.GRAY) else {
                        val c = if (newsHit(s)) Color.parseColor("#12B886") else Color.parseColor("#E5484D")
                        val e = when {
                            effect(s) > 0 -> Color.parseColor("#12B886")
                            effect(s) < 0 -> Color.parseColor("#E5484D")
                            else -> Color.GRAY
                        }
                        mapOf(8 to c, 9 to c, 10 to e)
                    }
                )
            }
        }
    }

    /** @param cellColors optional per-cell colour overriding [color] (the news columns are coloured by their own
     * hit/miss, not by the model-alone result that colours the rest of the row). */
    private fun addRow(parent: LinearLayout, cells: List<String>, bold: Boolean, color: Int?, cellColors: Map<Int, Int> = emptyMap()) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val widthPx = (columnWidthDp * resources.displayMetrics.density).toInt()
        cells.forEachIndexed { i, text ->
            row.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                gravity = Gravity.START
                setPadding(16, 12, 16, 12)
                layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                (cellColors[i] ?: color)?.let { setTextColor(it) }
            })
        }
        parent.addView(row)
    }
}
