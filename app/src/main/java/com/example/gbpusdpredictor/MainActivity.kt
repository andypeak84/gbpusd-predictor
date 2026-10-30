package com.example.gbpusdpredictor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.view.View
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.gbpusdpredictor.BuildConfig
import com.example.gbpusdpredictor.data.AppDatabase
import com.example.gbpusdpredictor.data.MarketData
import com.example.gbpusdpredictor.ml.TreeModelPredictor
import com.example.gbpusdpredictor.repository.MarketRepository
import com.example.gbpusdpredictor.sentiment.SentimentAdjustment
import com.example.gbpusdpredictor.sentiment.SentimentRepository
import com.example.gbpusdpredictor.ui.RateChartView
import com.example.gbpusdpredictor.utils.AppClock
import com.example.gbpusdpredictor.work.LiveRate
import com.example.gbpusdpredictor.work.MarketDataScheduler
import com.example.gbpusdpredictor.work.SentimentScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Further data" detail screen -- what the home-screen widget opens when tapped. Shows
 * the full feature row behind the current prediction, a history chart, and a 1-7 day
 * horizon selector for the forecast.
 *
 * No manual refresh here any more (no "Refresh Now" button) -- MarketDataScheduler's
 * single 21:00 UTC daily job is the only thing that ever writes a row now, per explicit
 * request. This screen is purely a read/view onto whatever's already in the database.
 */
/** The developer's Google Form for feature suggestions ("Suggest a feature" button). Blank hides the button. */
private const val FEEDBACK_FORM_URL = ""

class MainActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var detailSection: LinearLayout
    // Built lazily on a background thread the first time loadAndRender() runs (see there) --
    // constructing it reads and parses the ~120 KB / 150-tree model JSON from assets, which
    // is too much work for the main thread on activity launch. Null until that first load
    // completes; every call site null-checks.
    private var treeModel: TreeModelPredictor? = null
    private var latestData: MarketData? = null

    // The shared live GBP/USD reading (work/LiveRate) and the three lines that show it under the big number. The forecast and its scoring
    // stay tied to the daily ECB row; these lines say where the pound is NOW.
    private var liveReading: LiveRate.Reading? = null
    private var liveRateView: TextView? = null
    private var liveSourceView: TextView? = null
    private var refNoteView: TextView? = null
    private var selectedHorizonDays: Int = MarketRepository.DEFAULT_HORIZON_DAYS.coerceIn(1, 7)

    // Multi-horizon compare graph: normally tapping a day button single-selects it
    // (replacing selectedDays entirely, like a radio button). Long-pressing ANY day
    // button toggles multiSelectMode on; once on, every tap (not just long-press) adds
    // or removes that day from selectedDays instead of replacing it, so several horizons
    // can be plotted together. Long-pressing again turns multi-select back off and
    // collapses selectedDays down to one. Each day (1-7) always maps to the same colour
    // below, on both its button and its chart line, regardless of which days are
    // currently selected.
    private var multiSelectMode: Boolean = false
    private val selectedDays: MutableSet<Int> = linkedSetOf(MarketRepository.DEFAULT_HORIZON_DAYS.coerceIn(1, 7))
    private val dayButtons: MutableMap<Int, Button> = mutableMapOf()
    private val dayColors: Map<Int, Int> = mapOf(
        1 to Color.parseColor("#4A90D9"),
        2 to Color.parseColor("#7B61FF"),
        3 to Color.parseColor("#F5A623"),
        4 to Color.parseColor("#12B886"),
        5 to Color.parseColor("#E5484D"),
        6 to Color.parseColor("#D6409F"),
        7 to Color.parseColor("#8B5E34")
    )

    private lateinit var chartView: RateChartView
    private lateinit var predictionLine: TextView
    private lateinit var recommendationLine: TextView
    private lateinit var amountLine: TextView
    private lateinit var horizonButtonsRow: LinearLayout
    private lateinit var sentimentStatusLine: TextView
    // Every story in the 24h news reading, each with a Flag button (v88), plus "Export my flags".
    private lateinit var sentimentHeadlineList: LinearLayout
    private var showAllHeadlines = false
    private lateinit var sentimentEffectLine: TextView
    private lateinit var sentimentControls: LinearLayout

    // Optional "I want to convert £X" amount, so the recommendation can be shown in actual
    // dollars, not just a rate. Persisted in its own prefs file; null = not set.
    private var gbpAmount: Double? = null

    // The forecast text's normal colour, restored whenever the news-sentiment overlay is off
    // (the forecast turns blue while it is included -- see renderPrediction).
    private var predictionDefaultColor: Int = Color.BLACK

    // Newest stored row is considered stale once it's this many BUSINESS days behind the
    // date the daily refresh should have reached by now (AppClock.today() minus
    // MarketRepository.TARGET_LAG_BUSINESS_DAYS). 3 = roughly a full trading week with no
    // successful capture -- comfortably past normal source-publish lag, so it only fires
    // when the refresh has genuinely stopped.
    private val staleBusinessDaysThreshold = 3

    // Includes the date, not just HH:mm -- Date and Captured can legitimately differ by a
    // day or more now (Date is the trading day the row is FOR, Captured is when the
    // refresh actually ran to fetch it -- normally the day after, since it waits for that
    // trading day to fully settle). Showing only a time made a perfectly correct row look
    // wrong: a real report mistook "Captured 19:23" sitting next to "Date 2026-08-18" as
    // the same moment, when capturedAtUtc's date portion (hidden by the old HH:mm-only
    // format) was actually the following day.
    private val capturedAtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

    private var cachedChartHistory: List<RateChartView.Point> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gbpAmount = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
            .getString(KEY_GBP_AMOUNT, null)?.toDoubleOrNull()

        maybeRequestNotificationPermission()

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }
        com.example.gbpusdpredictor.ui.ScreenHost.install(this, ScrollView(this).apply { addView(container) })

        loadAndRender()
    }

    /** API 33+ gates notifications behind a runtime grant. Fire-and-forget: the daily
     * notification (MarketDataWorker.postForecastNotification) simply no-ops if this is
     * denied, so there's no result callback to handle -- the system remembers the choice
     * and won't re-prompt. On API < 33 POST_NOTIFICATIONS is granted at install time. */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        // Purely a re-render of whatever's already stored -- cheap, and picks up a row
        // the 21:00 UTC job may have written while the app was in the background. No
        // network call happens here.
        liveReading = LiveRate.cached(this)   // show what is already known at once; refreshed just below
        loadAndRender()
        refreshLiveRate()
    }

    /** At most one fetch (the shared store reuses a fresh reading, and counts Alpha Vantage calls: 25 a day on the free tier). */
    private fun refreshLiveRate() {
        CoroutineScope(Dispatchers.Main).launch {
            val reading = withContext(Dispatchers.IO) { runCatching { LiveRate.current(applicationContext) }.getOrNull() }
            if (reading != null) liveReading = reading
            updateLiveLine()
            // the "£ amount" line uses the live rate too
            latestData?.let { if (::predictionLine.isInitialized) renderPrediction(it) }
        }
    }

    /** A live reading younger than 12 h, or null (then the screen shows the daily ECB rate and says so). */
    private fun currentLive(): LiveRate.Reading? =
        liveReading?.takeIf { java.time.Duration.between(it.at, Instant.now()).toHours() < 12 }

    private fun updateLiveLine(latest: MarketData? = latestData) {
        val ref = latest?.gbpUsd
        val now = Instant.now()
        val live = currentLive()
        liveRateView?.text = if (live != null) "Live rate: ${"%.4f".format(Locale.UK, live.rate)}"
            else "Current rate: ${fmt(ref, "%.4f")}"
        liveSourceView?.text = if (live != null) {
            "${live.source.label} · ${DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(live.at)} · ${LiveRate.ageLabel(live, now)}"
        } else "Daily ECB rate -- no live rate available (add an Alpha Vantage key in Price Alerts, or check your connection)"
        refNoteView?.text = if (ref != null && latest != null) buildString {
            append("Daily reference (ECB, ${latest.date}): ${"%.4f".format(Locale.UK, ref)}. Forecasts below start from it")
            if (live != null) append("; live is ${"%+.2f%%".format(Locale.UK, (live.rate / ref - 1) * 100)} against it")
            append(".")
        } else ""
    }

    private fun loadAndRender() {
        container.removeAllViews()
        addLine(container, "Loading latest data...", 18f, Gravity.START)

        CoroutineScope(Dispatchers.Main).launch {
            val (latest, dailyHistoryOldestFirst) = withContext(Dispatchers.IO) {
                // First render builds the tree model here (off the main thread) -- it
                // parses a ~120 KB JSON into 150 trees. Kept for the life of the activity.
                if (treeModel == null) treeModel = TreeModelPredictor(applicationContext)
                val dao = AppDatabase.getInstance(applicationContext).marketDao()
                // One row per trading day now (see MarketData.kt's v5->v6 note) --
                // getLatest() is simply the most recent trading date on record.
                val latest = dao.getLatest()
                val dailyHistory = dao.getRecentDailyEntries(60)
                latest to dailyHistory
            }
            latestData = latest

            container.removeAllViews()
            addTopButtons(container)

            addLine(container, heartbeatLabel(), 11f, Gravity.START)
            addLine(container, nextRefreshLabel(), 11f, Gravity.START)

            if (latest == null) {
                addLine(container, "No data yet. The daily refresh runs at 21:00 UTC -- check back after that, or see Logcat 'MarketRepository'/'MarketDataWorker'.", 18f, Gravity.START)
                return@launch
            }

            stalenessBanner(latest)?.let { addBanner(container, it) }

            addLine(container, "GBP/USD Predictor", 22f, Gravity.START, bold = true)
            // versionName is now synced to the newgdp-vNN zip numbering this project ships
            // under (see app/build.gradle) -- bump both together on every delivered zip.
            addLine(container, "v${BuildConfig.VERSION_NAME}" + if (BuildConfig.TEST_ADS) "  ·  closed-test build: test ads only" else "", 11f, Gravity.START)
            // Permanent, on the first screen (Google Play's finance policy and basic honesty: the
            // forecasts are about 50% right in the app's own Accuracy screen).
            addLine(container, "Informational only. Not financial advice. Forecasts are statistical estimates and are often wrong.", 11f, Gravity.START)
            addLine(container, "Data for trading day: ${latest.date}  (fetched ${capturedAtLabel(latest)})", 13f, Gravity.START)

            // Nulls are real here now (see MarketData.kt) -- a chart point needs a real
            // gbpUsd, so dates without one are simply skipped rather than plotted as 0.
            cachedChartHistory = dailyHistoryOldestFirst
                .sortedBy { it.date }
                .mapNotNull { row -> row.gbpUsd?.let { RateChartView.Point(LocalDate.parse(row.date), it) } }

            detailSection = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            container.addView(detailSection)

            renderDetailSection(latest)
        }
    }

    private fun capturedAtLabel(row: MarketData): String = try {
        "${capturedAtFormatter.format(Instant.parse(row.capturedAtUtc))} UTC"
    } catch (e: Exception) {
        row.date
    }

    /** Shows whether the scheduled worker has actually been firing, independent of
     * whether it found anything new to capture. Without this, a run that correctly found
     * today's target already captured (the normal outcome most days -- every weekend run,
     * for instance) was indistinguishable from the job never running at all, since that
     * outcome leaves no market_data row and no bootstrap_fetch_log entries -- confirmed
     * via a real report where working out "is this actually a problem" required
     * reconstructing the business-day math by hand. Read directly from what
     * MarketDataWorker itself last recorded, so this reflects reality even when the
     * outcome was SKIPPED_ALREADY_CAPTURED or FAILED and left nothing else to look at. */
    private fun heartbeatLabel(): String {
        val prefs = getSharedPreferences(
            com.example.gbpusdpredictor.work.MarketDataWorker.PREFS_NAME,
            MODE_PRIVATE
        )
        val lastAttempt = prefs.getString(com.example.gbpusdpredictor.work.MarketDataWorker.KEY_LAST_ATTEMPT_UTC, null)
        val lastOutcome = prefs.getString(com.example.gbpusdpredictor.work.MarketDataWorker.KEY_LAST_OUTCOME, null)
        val attemptNumber = prefs.getInt(com.example.gbpusdpredictor.work.MarketDataWorker.KEY_LAST_ATTEMPT_NUMBER, 1)

        if (lastAttempt == null) return "Last refresh attempt: never (the daily job hasn't run yet on this install)."

        val attemptTime = try {
            "${capturedAtFormatter.format(Instant.parse(lastAttempt))} UTC"
        } catch (e: Exception) {
            lastAttempt
        }
        val outcomeLabel = when (lastOutcome) {
            "SUCCESS" -> "captured successfully"
            "SKIPPED_ALREADY_CAPTURED" -> "skipped -- today's target was already captured (normal, e.g. weekend runs)"
            "FAILED" -> if (attemptNumber > 1) "failed, retry $attemptNumber in progress" else "failed"
            null -> "in progress or crashed before completing"
            else -> lastOutcome
        }
        return "Last refresh attempt: $attemptTime -- $outcomeLabel"
    }

    /** Countdown to the next scheduled daily refresh anchor (MarketDataScheduler's own
     * 21:00 UTC choice -- see its doc comment for why that specific time). Purely the
     * schedule's TARGET time, recomputed fresh from the current moment each render -- not
     * a live prediction of exactly when the next attempt will actually fire: Doze/battery
     * deferral can push a run later (the whole reason MarketDataScheduler is a
     * self-rescheduling chain and not a PeriodicWorkRequest), and a FAILED run's own retry
     * backoff (see heartbeatLabel's "retry N in progress" above) can make the very next
     * attempt happen well before this anchor. Read alongside the heartbeat line above, not
     * instead of it -- this answers "when's the next refresh normally due," that answers
     * "did the last one actually run." */
    private fun nextRefreshLabel(): String {
        val millisUntil = MarketDataScheduler.millisUntilNextUtc(
            MarketDataScheduler.REFRESH_HOUR_UTC,
            MarketDataScheduler.REFRESH_MINUTE_UTC
        )
        val totalMinutes = (millisUntil / 60_000L).coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val countdown = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        return "Next scheduled refresh: in $countdown (%02d:%02d UTC daily)".format(Locale.UK, MarketDataScheduler.REFRESH_HOUR_UTC, MarketDataScheduler.REFRESH_MINUTE_UTC)
    }

    /** Loud banner text when the newest stored row has fallen well behind where the daily
     * refresh should be by now -- the self-rescheduling worker chain can stall silently
     * (a crash before refreshData()'s own try/catch, force-stop, a multi-day source
     * outage), and the small heartbeat line above is too quiet to catch that. Returns
     * null when data is current, or within normal source-publish lag. Compared in BUSINESS
     * days so a normal weekend doesn't trip it. */
    private fun stalenessBanner(latest: MarketData): String? {
        val latestDate = try { LocalDate.parse(latest.date) } catch (e: Exception) { return null }
        val expectedTarget = AppClock.businessDaysBack(AppClock.today(), MarketRepository.TARGET_LAG_BUSINESS_DAYS)
        if (!latestDate.isBefore(expectedTarget)) return null
        val behind = AppClock.businessDaysBetween(latestDate, expectedTarget)
        if (behind < staleBusinessDaysThreshold) return null
        return "⚠ Data looks stale. Newest reading is for ${latest.date}, about $behind business " +
            "day(s) behind where the daily refresh should be by now -- it may have stopped running. " +
            heartbeatLabel()
    }

    /** Null-safe display formatting -- every raw/derived market field is now Double?
     * (see MarketData.kt's v5->v6 note): a field is either a real, date-verified reading
     * or genuinely absent, never a fabricated stand-in. "—" means exactly that: this
     * refresh had nothing real for this field on this date -- check the Bootstrap Log
     * screen (via View History) to see why, rather than guessing from a placeholder
     * number. */
    private fun fmt(value: Double?, pattern: String): String = value?.let { pattern.format(Locale.UK, it) } ?: "—"
    private fun fmtPct(value: Double?): String = value?.let { "%+.2f%%".format(Locale.UK, it * 100) } ?: "—"

    /** Everything that depends on the current row -- current rate, chart, horizon-based
     * forecast, feature breakdown. */
    private fun renderDetailSection(latest: MarketData) {
        detailSection.removeAllViews()

        liveRateView = TextView(this).apply { textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 8, 0, 0) }
        liveSourceView = TextView(this).apply { textSize = 12f }
        refNoteView = TextView(this).apply { textSize = 12f; setPadding(0, 0, 0, 8) }
        detailSection.addView(liveRateView)
        detailSection.addView(liveSourceView)
        detailSection.addView(refNoteView)
        updateLiveLine(latest)
        if (latest.gbpUsd == null) {
            addLine(detailSection, "No real GBP/USD reading for ${latest.date} -- see the Bootstrap Log (View History) for why.", 12f, Gravity.START)
        }
        // Toggle sits right under the current rate so it is visible on opening the app --
        // an earlier placement just above the forecast text was below the fold and was
        // not found. Its effect shows in the forecast further down ("model x / news y").
        addSentimentSection(detailSection)
        addSpacer(detailSection)

        chartView = RateChartView(this@MainActivity).apply {
            // A custom-drawn chart is invisible to TalkBack unless it is described.
            contentDescription = "Line chart of the GBP/USD rate over recent weeks, with the forecast shown as a dashed line"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (280 * resources.displayMetrics.density).toInt()
            )
        }
        detailSection.addView(chartView)
        addSpacer(detailSection)

        addLine(detailSection, "Forecast horizon", 16f, Gravity.START, bold = true)
        addLine(detailSection, "Tap a day to select it. Long-press any day to compare multiple horizons at once.", 12f, Gravity.START)
        horizonButtonsRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            // MATCH_PARENT so the 7 equal-weight buttons (see addHorizonButton) actually
            // divide up the full screen width instead of just wrap-sizing to their
            // smallest natural width -- weight only distributes space within a row that's
            // already constrained to a known width.
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        detailSection.addView(horizonButtonsRow)
        dayButtons.clear()
        (1..7).forEach { days -> addHorizonButton(days) }
        updateButtonStyles()

        addAmountInput(detailSection)

        predictionLine = TextView(this@MainActivity).apply { textSize = 18f; setPadding(0, 16, 0, 8) }
        predictionDefaultColor = predictionLine.currentTextColor
        recommendationLine = TextView(this@MainActivity).apply { textSize = 15f; setPadding(0, 8, 0, 8) }
        amountLine = TextView(this@MainActivity).apply { textSize = 15f; setPadding(0, 4, 0, 8) }
        detailSection.addView(predictionLine)
        detailSection.addView(recommendationLine)
        detailSection.addView(amountLine)

        renderPrediction(latest)

        addSpacer(detailSection)
        addLine(detailSection, "Features behind this prediction", 16f, Gravity.START, bold = true)
        addLine(detailSection, "UK 2yr yield (auto-fetched): ${fmt(latest.ukRate, "%.3f")}%", 14f, Gravity.START)
        addLine(detailSection, "US 2yr yield: ${fmt(latest.us2y, "%.3f")}%", 14f, Gravity.START)
        addLine(detailSection, "Spread (UK - US 2yr): ${fmt(latest.spread2y, "%.3f")}", 14f, Gravity.START)
        addLine(detailSection, "VIX: ${fmt(latest.vix, "%.2f")}", 14f, Gravity.START)
        addLine(detailSection, "Oil (Brent) 5d change: ${fmtPct(latest.brentChange5d)}", 14f, Gravity.START)
        addLine(detailSection, "GBP/USD 5d change: ${fmtPct(latest.gbpUsdChange5d)}", 14f, Gravity.START)
        addLine(detailSection, "GBP/EUR 5d change: ${fmtPct(latest.gbpEurChange5d)}", 14f, Gravity.START)
        addLine(detailSection, "EUR/USD 5d change: ${fmtPct(latest.eurUsdChange5d)}", 14f, Gravity.START)
        addLine(detailSection, "USD basket 5d change (vs EUR/JPY/CAD/CHF): ${fmtPct(latest.usdBasketChange5d)}", 14f, Gravity.START)
        addLine(detailSection, "GBP/USD realised vol (10d, daily σ): ${fmt(latest.gbpUsdRealVol10d, "%.4f")}", 14f, Gravity.START)
        addLine(detailSection, "RSI(14): ${fmt(latest.rsi14, "%.1f")}", 14f, Gravity.START)
        addLine(detailSection, "US 2yr yield 5d change: ${fmtPct(latest.us2yChange5d)}", 14f, Gravity.START)
        addLine(detailSection, "VIX 5d change: ${fmtPct(latest.vixChange5d)}", 14f, Gravity.START)
        addLine(detailSection, "GBP/USD 1d change: ${fmtPct(latest.gbpUsdChange1d)}", 14f, Gravity.START)
        addLine(detailSection, "GBP/USD 10d change: ${fmtPct(latest.gbpUsdChange10d)}", 14f, Gravity.START)
        addLine(detailSection, "GBP/USD 20d change: ${fmtPct(latest.gbpUsdChange20d)}", 14f, Gravity.START)
        addLine(detailSection, "Spread (UK-US 2yr) 10d change: ${fmtPct(latest.spread2yChange10d)}", 14f, Gravity.START)
        addLine(detailSection, "USD basket 1d change (vs EUR/JPY/CAD/CHF): ${fmtPct(latest.usdBasketChange1d)}", 14f, Gravity.START)
        addLine(
            detailSection,
            "A \u201c—\u201d above means this field had no real, date-verified reading for ${latest.date} -- it was left out of the prediction (the model handles missing features natively), not filled in with a guess. See Bootstrap Log for why.",
            11f, Gravity.START
        )
    }

    /** Optional GBP amount so the forecast can be shown in real dollars, not just a rate.
     * Persisted across launches. "Apply" with an empty/invalid box clears it. Re-renders
     * the prediction in place rather than the whole screen. */
    private fun addAmountInput(parent: LinearLayout) {
        addLine(parent, "Amount to convert (optional)", 14f, Gravity.START, bold = true)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val field = EditText(this).apply {
            hint = "£ amount"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            gbpAmount?.let { setText(it.toString()) }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val apply = Button(this).apply {
            text = "Apply"
            setOnClickListener {
                val parsed = field.text?.toString()?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }
                gbpAmount = parsed
                getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                    .apply { if (parsed == null) remove(KEY_GBP_AMOUNT) else putString(KEY_GBP_AMOUNT, parsed.toString()) }
                    .apply()
                latestData?.let { renderPrediction(it) }
            }
        }
        row.addView(field)
        row.addView(apply)
        parent.addView(row)
    }

    private fun addHorizonButton(days: Int) {
        // 7 plain Buttons in a row overflowed a portrait-width screen (each one uses the
        // Material default ~88dp minWidth, 7x that is way past a ~360-400dp phone width),
        // so only the first few were reachable without rotating to landscape. Zeroing
        // minWidth/minHeight and giving each button equal weight on a MATCH_PARENT row
        // makes all 7 share the available width and shrink to fit portrait -- same fix
        // shape as chartView's inline dp conversion above.
        val marginPx = (2 * resources.displayMetrics.density).toInt()
        val button = Button(this).apply {
            text = "${days}d"
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, paddingTop, 0, paddingBottom)
            setOnClickListener { onDayTapped(days) }
            setOnLongClickListener { onDayLongPressed(days); true }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = marginPx
                marginEnd = marginPx
            }
        }
        dayButtons[days] = button
        horizonButtonsRow.addView(button)
    }

    private fun onDayTapped(days: Int) {
        if (!multiSelectMode) {
            selectedHorizonDays = days
            selectedDays.clear()
            selectedDays.add(days)
        } else {
            if (selectedDays.contains(days)) {
                if (selectedDays.size > 1) selectedDays.remove(days)
            } else {
                selectedDays.add(days)
            }
        }
        updateButtonStyles()
        latestData?.let { renderPrediction(it) }
    }

    private fun onDayLongPressed(days: Int) {
        multiSelectMode = !multiSelectMode
        if (multiSelectMode) {
            selectedDays.add(days)
            Toast.makeText(this, "Compare mode on — tap days to add/remove them", Toast.LENGTH_SHORT).show()
        } else {
            val keep = if (selectedDays.contains(days)) days else selectedDays.maxOrNull() ?: selectedHorizonDays
            selectedDays.clear()
            selectedDays.add(keep)
            selectedHorizonDays = keep
            Toast.makeText(this, "Compare mode off", Toast.LENGTH_SHORT).show()
        }
        updateButtonStyles()
        latestData?.let { renderPrediction(it) }
    }

    private fun updateButtonStyles() {
        dayButtons.forEach { (days, button) ->
            if (selectedDays.contains(days)) {
                button.background = ColorDrawable(dayColors.getValue(days))
                button.setTextColor(Color.WHITE)
            } else {
                button.background = null
                button.setTextColor(predictionDefaultColor)
            }
        }
    }

    /** The on/off toggle for the news-sentiment overlay and a status line. When ON, renderPrediction() shifts every displayed forecast (and the advice
     * line, which is derived from it) by SentimentAdjustment.tilt(). OFF by default: it is
     * an unvalidated judgement-sized nudge, not part of the trained model -- see
     * SentimentRepository's top comment. */
    private fun addSentimentSection(parent: LinearLayout) {
        addSpacer(parent)
        val checkbox = CheckBox(this).apply {
            text = "Use news sentiment"
            textSize = 15f
            isChecked = SentimentRepository.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                SentimentRepository.setEnabled(this@MainActivity, on)
                sentimentControls.visibility = if (on) View.VISIBLE else View.GONE
                if (on) {
                    SentimentScheduler.startFresh(this@MainActivity)
                    refreshSentimentAsync()
                } else {
                    SentimentScheduler.stop(this@MainActivity)
                }
                updateSentimentStatus()
                latestData?.let { renderPrediction(it) }
            }
        }
        parent.addView(checkbox)
        sentimentStatusLine = TextView(this).apply { textSize = 12f; setPadding(0, 0, 0, 4) }
        parent.addView(sentimentStatusLine)
        sentimentHeadlineList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 8) }
        parent.addView(sentimentHeadlineList)
        // Filled in by renderPrediction(): what the news does to the forecast of the selected horizon(s).
        sentimentEffectLine = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, 4); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        parent.addView(sentimentEffectLine)

        // Weighting controls, shown only while the toggle is on. Both are saved on the phone and
        // the forecast above re-renders live as a slider moves. Reset puts back the defaults.
        sentimentControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (checkbox.isChecked) View.VISIBLE else View.GONE
        }
        val weightLabel = TextView(this).apply { textSize = 13f }
        val weightBar = SeekBar(this).apply {
            max = Math.round(SentimentAdjustment.MAX_WEIGHT_OF_HORIZON_SD * 100).toInt()
            contentDescription = "News weight slider: how strongly news moves the forecast"
        }
        val storiesLabel = TextView(this).apply { textSize = 13f; setPadding(0, 8, 0, 0) }
        val storiesBar = SeekBar(this).apply {
            max = SentimentAdjustment.MAX_FULL_STRENGTH_STORIES - 1
            contentDescription = "Full strength slider: how many stories are needed for the full news effect"
        }
        fun syncControls() {
            val weight = SentimentRepository.weight(this)
            val stories = SentimentRepository.fullStrengthStories(this)
            weightBar.progress = Math.round(weight * 100).toInt()
            storiesBar.progress = stories - 1
            weightLabel.text = "News weight: ${"%.2f".format(Locale.UK, weight)} × the typical move  (0 = no effect, ${"%.1f".format(Locale.UK, SentimentAdjustment.MAX_WEIGHT_OF_HORIZON_SD)} = maximum)"
            storiesLabel.text = "Full strength at $stories ${if (stories == 1) "story" else "stories"}  (fewer stories scale the effect down)"
        }
        syncControls()
        weightBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                SentimentRepository.setWeight(this@MainActivity, progress / 100.0)
                syncControls()
                latestData?.let { renderPrediction(it) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        storiesBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                SentimentRepository.setFullStrengthStories(this@MainActivity, progress + 1)
                syncControls()
                latestData?.let { renderPrediction(it) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        val resetButton = Button(this).apply {
            text = "Reset weighting to defaults"
            textSize = 11f
            setOnClickListener {
                SentimentRepository.resetTuning(this@MainActivity)
                syncControls()
                latestData?.let { renderPrediction(it) }
            }
        }
        sentimentControls.addView(weightLabel)
        sentimentControls.addView(weightBar)
        sentimentControls.addView(storiesLabel)
        sentimentControls.addView(storiesBar)
        sentimentControls.addView(resetButton)
        parent.addView(sentimentControls)
        updateSentimentStatus()
        if (checkbox.isChecked) refreshSentimentAsync()
    }

    private fun updateSentimentStatus(fetchError: String? = null) {
        val enabled = SentimentRepository.isEnabled(this)
        val reading = SentimentRepository.reading(this)
        sentimentStatusLine.text = when {
            !enabled -> "Off — the forecast below is the model alone."
            reading == null -> "No recent news reading${fetchError?.let { " ($it)" } ?: ""} — the forecast below is the model alone."
            else -> {
                val age = reading.ageHours?.let {
                    if (it < 1.0) "${(it * 60).toInt()} min ago" else "${"%.1f".format(Locale.UK, it)} h ago"
                } ?: "just now"
                val fewNote = if (reading.articles < SentimentRepository.fullStrengthStories(this)) " Few stories, so the shift is scaled down." else ""
                "News (24h): ${reading.label}, ${"%+.2f".format(Locale.UK, reading.score)} from ${reading.articles} scored article(s), updated $age.$fewNote"
            }
        }
        renderHeadlineList(if (enabled) reading else null)
    }

    /** The full list of stories behind the 24h reading (scored ones first, then neutral), each with a Flag button so
     * the owner can mark one bullish / neutral / bearish. A flag changes this phone's reading at once
     * (SentimentRepository.reading) and is kept for export as ratings.csv rows. */
    private fun renderHeadlineList(reading: com.example.gbpusdpredictor.sentiment.SentimentReading?) {
        sentimentHeadlineList.removeAllViews()
        if (reading == null) return
        val flags = com.example.gbpusdpredictor.sentiment.SentimentFlags.directions(this)
        val items = reading.headlines.filter { it.in24h }
            .sortedWith(compareBy<com.example.gbpusdpredictor.sentiment.SentimentHeadline> { it.direction == 0 }.thenByDescending { it.publishedTs })
        val shown = if (showAllHeadlines) items else items.take(5)
        fun arrow(d: Int) = when { d > 0 -> "▲"; d < 0 -> "▼"; else -> "•" }
        shown.forEach { h ->
            val mine = flags[h.flagKey]
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                textSize = 12f
                setPadding(0, 6, 8, 6)
                text = buildString {
                    append("${arrow(h.direction)} ${h.source}: ${h.displayText}")
                    if (mine != null) append("   [your flag: ${arrow(mine)}]")
                }
                if (mine != null && mine != h.direction) setTextColor(com.example.gbpusdpredictor.ui.Night.accentBlue(this@MainActivity))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = if (mine == null) "Flag" else "Flagged"
                // Every row says just "Flag"; TalkBack needs to know which story it belongs to.
                contentDescription = (if (mine == null) "Flag story: " else "Change your flag on story: ") + h.displayText.take(100)
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { showFlagDialog(h) }
            })
            sentimentHeadlineList.addView(row)
        }
        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (items.size > 5) links.addView(Button(this).apply {
            text = if (showAllHeadlines) "Show fewer" else "Show all ${items.size}"
            textSize = 11f
            setOnClickListener { showAllHeadlines = !showAllHeadlines; updateSentimentStatus() }
        })
        val flagCount = com.example.gbpusdpredictor.sentiment.SentimentFlags.count(this)
        if (flagCount > 0) links.addView(Button(this).apply {
            text = "Export my flags ($flagCount)"
            textSize = 11f
            setOnClickListener {
                val csv = com.example.gbpusdpredictor.sentiment.SentimentFlags.exportCsv(this@MainActivity)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "GBP/USD headline flags")
                    putExtra(Intent.EXTRA_TEXT, csv)
                }, "Export flags"))
            }
        })
        sentimentHeadlineList.addView(links)
    }

    private fun showFlagDialog(h: com.example.gbpusdpredictor.sentiment.SentimentHeadline) {
        val options = arrayOf("▲ Bullish for the pound", "• Neutral", "▼ Bearish for the pound", "Clear my flag")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(h.displayText.take(120))
            .setItems(options) { _, which ->
                val mine = when (which) { 0 -> 1; 1 -> 0; 2 -> -1; else -> null }
                com.example.gbpusdpredictor.sentiment.SentimentFlags.set(this, h, mine)
                updateSentimentStatus()
                latestData?.let { renderPrediction(it) }
            }
            .show()
    }

    private fun refreshSentimentAsync() {
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { runCatching { SentimentRepository.refresh(this@MainActivity) } }
            updateSentimentStatus(result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
            latestData?.let { renderPrediction(it) }
        }
    }

    private fun renderPrediction(latest: MarketData) {
        amountLine.text = ""
        sentimentEffectLine.text = ""
        val gbpUsd = latest.gbpUsd
        if (gbpUsd == null) {
            refreshChartHistory(emptyList())
            predictionLine.text = "No forecast available -- no real GBP/USD reading for ${latest.date} to scale a % change onto."
            recommendationLine.text = ""
            return
        }

        val orderedDays = selectedDays.sorted()
        val model = treeModel
        // News-sentiment overlay (see SentimentRepository's own comment): null unless the
        // user has switched it on AND there is a usable reading. modelChanges keeps the
        // model's own number so the screen can show it separately from the shifted one.
        val sentiment = if (SentimentRepository.isEnabled(this)) SentimentRepository.reading(this) else null
        val modelChanges = mutableMapOf<Int, Double>()
        val predictions: List<Triple<Int, Double, Double>> = if (model == null) emptyList() else orderedDays.mapNotNull { days ->
            val modelChange = model.predictChangePct(MarketRepository.featuresFrom(latest, days))
            modelChange?.let {
                modelChanges[days] = it
                val tilt = sentiment?.let { s -> SentimentAdjustment.tilt(s.score, s.articles, model.horizonSdPct(days), SentimentRepository.weight(this), SentimentRepository.fullStrengthStories(this)) } ?: 0.0
                val changePct = it + tilt
                Triple(days, changePct, gbpUsd * (1.0 + changePct))
            }
        }
        if (sentiment != null && predictions.isNotEmpty()) {
            sentimentEffectLine.text = "News effect on the pound: " + predictions.joinToString(", ") { (days, changePct, _) ->
                val shift = changePct - (modelChanges[days] ?: changePct)
                "${days}d ${"%+.2f".format(Locale.UK, shift * 100)}%"
            }
        }
        // "model +0.05% / news -0.03%" breakdown for a horizon, or "" when the overlay is
        // off, unavailable, or rounds to nothing at this horizon.
        val sentimentNote = { days: Int, changePct: Double ->
            val modelChange = modelChanges[days]
            if (sentiment == null || modelChange == null) "" else {
                val tilt = changePct - modelChange
                "  ★ [model ${"%+.2f".format(Locale.UK, modelChange * 100)}% / news ${"%+.2f".format(Locale.UK, tilt * 100)}%]"
            }
        }

        // Trading days, not calendar days: a Friday "1d" forecast is for Monday (as the Accuracy screen scores it).
        val targetDateFor = { days: Int -> AppClock.businessDaysForward(LocalDate.parse(latest.date), days) }
        refreshChartHistory(predictions.map { (days, _, predictedRate) ->
            RateChartView.PredictedLine(
                point = RateChartView.Point(targetDateFor(days), predictedRate),
                color = dayColors.getValue(days),
                label = "${days}d"
            )
        })

        if (predictions.isEmpty()) {
            predictionLine.text = "No forecast available (tree model not loaded — see Logcat 'TreeModelPredictor')."
            recommendationLine.text = ""
            return
        }

        val bandFor = { days: Int, rate: Double ->
            model?.maePctFor(days)?.let { mae -> "  ± ${"%.4f".format(Locale.UK, rate * mae)}" } ?: ""
        }

        if (predictions.size == 1) {
            val (days, changePct, predictedRate) = predictions.first()
            val targetDate = targetDateFor(days)
            val base = "$days-day forecast ($targetDate): ${"%.4f".format(Locale.UK, predictedRate)}${bandFor(days, predictedRate)}  (${"%+.2f".format(Locale.UK, changePct * 100)}%)"
            val modelChange = modelChanges[days]
            // Say plainly which number this is: the model alone, or the model plus the news shift
            // (then also shown in blue, with the breakdown: model alone + news = this forecast).
            predictionLine.text = when {
                sentiment != null && modelChange != null -> {
                    val modelRate = gbpUsd * (1.0 + modelChange)
                    "$base\n★ INCLUDES NEWS SENTIMENT\nmodel alone ${"%.4f".format(Locale.UK, modelRate)} (${"%+.2f".format(Locale.UK, modelChange * 100)}%)" +
                        " + news ${"%+.2f".format(Locale.UK, (changePct - modelChange) * 100)}% = ${"%.4f".format(Locale.UK, predictedRate)}"
                }
                SentimentRepository.isEnabled(this) -> "$base\n(model only — no recent news reading)"
                else -> "$base\n(model only)"
            }
            predictionLine.setTextColor(if (sentiment != null) com.example.gbpusdpredictor.ui.Night.accentBlue(this) else predictionDefaultColor)
            // Cutoff scales with this horizon's own typical move size (see
            // TreeModelPredictor.adviceCutoffPctFor's own comment) -- falls back to the
            // original fixed 0.15% for a model with no horizon_sd block.
            val cutoff = model?.adviceCutoffPctFor(days) ?: 0.0015
            recommendationLine.text = when {
                changePct > cutoff -> "Rate may rise — consider waiting before converting GBP to USD."
                changePct < -cutoff -> "Rate may fall — converting sooner may get you more USD per GBP."
                else -> "Rate looks broadly stable over this horizon."
            } + if (sentiment != null) "  (includes news sentiment)" else ""
            gbpAmount?.let { amount ->
                val refUsd = amount * gbpUsd
                val predUsd = amount * predictedRate
                val diff = predUsd - refUsd
                val liveNow = currentLive()?.rate
                amountLine.text = (if (liveNow != null) {
                    "£${"%,.2f".format(Locale.UK, amount)} → \$${"%,.2f".format(Locale.UK, amount * liveNow)} at the live rate now. " +
                        "Forecast \$${"%,.2f".format(Locale.UK, predUsd)} on $targetDate (${"%+,.2f".format(Locale.UK, diff)} against " +
                        "\$${"%,.2f".format(Locale.UK, refUsd)} at the ${latest.date} reference)"
                } else {
                    "£${"%,.2f".format(Locale.UK, amount)} → \$${"%,.2f".format(Locale.UK, refUsd)} at the daily rate " +
                        "vs. \$${"%,.2f".format(Locale.UK, predUsd)} on $targetDate  (${"%+,.2f".format(Locale.UK, diff)})"
                }) + if (sentiment != null) "  — incl. news sentiment" else ""
            }
        } else {
            predictionLine.text = predictions.joinToString("\n") { (days, changePct, predictedRate) ->
                "${days}d (${targetDateFor(days)}): ${"%.4f".format(Locale.UK, predictedRate)}${bandFor(days, predictedRate)}  (${"%+.2f".format(Locale.UK, changePct * 100)}%)" +
                    sentimentNote(days, changePct)
            }
            predictionLine.setTextColor(if (sentiment != null) com.example.gbpusdpredictor.ui.Night.accentBlue(this) else predictionDefaultColor)
            recommendationLine.text = "Comparing ${predictions.size} horizons — matching colours on the chart above and the day buttons below." +
                if (sentiment != null) "  ★ = includes news sentiment." else "  (model only)"
        }
    }

    private fun refreshChartHistory(predictedLines: List<RateChartView.PredictedLine>) {
        chartView.setData(
            historical = cachedChartHistory,
            predictedLines = predictedLines
        )
    }

    /** Two rows of two, each button given equal weighted width (MATCH_PARENT row,
     * weight=1 per button) -- four full-text buttons ("Bootstrap Log", "Price
     * Alerts", etc.) in a single fixed-width row would overflow or get clipped in
     * portrait, same class of bug as the horizon buttons (see addHorizonButton's own
     * comment) -- just four buttons instead of seven, so two rows of two fixes it
     * without needing that one's narrower "wrap, no min width" treatment. */
    private fun addTopButtons(parent: LinearLayout) {
        fun newRow() = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun weightedButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val row1 = newRow()
        row1.addView(weightedButton("Accuracy") { startActivity(Intent(this@MainActivity, AccuracyActivity::class.java)) })
        row1.addView(weightedButton("Price Alerts") { startActivity(Intent(this@MainActivity, PriceAlertSettingsActivity::class.java)) })
        parent.addView(row1)

        // The developer-style tools (forecast history with export/import, the data fetch log) plus the
        // ad-consent choices and the data credits live behind this one button for the public release.
        val row2 = newRow()
        row2.addView(weightedButton("Diagnostics, history & credits") { startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java)) })
        parent.addView(row2)

        // "What would you like to see in the app?" survey: a Google Form the developer owns, opened in the browser so
        // the app itself collects nothing. Hidden until FEEDBACK_FORM_URL is filled in (questions: play/survey.md).
        if (FEEDBACK_FORM_URL.isNotBlank()) {
            val row3 = newRow()
            row3.addView(weightedButton("Suggest a feature") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(FEEDBACK_FORM_URL)))
                } catch (e: android.content.ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "No browser found", Toast.LENGTH_LONG).show()
                }
            })
            parent.addView(row3)
        }

        addSpacer(parent)
    }

    private fun addLine(parent: LinearLayout, text: String, sizeSp: Float, gravity: Int, bold: Boolean = false) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = sizeSp
            this.gravity = gravity
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        })
    }

    /** Full-width, white-on-red, bold -- for the staleness warning only, so it's
     * impossible to mistake for the ordinary small-print status lines. */
    private fun addBanner(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E5484D"))
            setPadding(24, 20, 24, 20)
        })
        addSpacer(parent)
    }

    private fun addSpacer(parent: LinearLayout) {
        parent.addView(TextView(this).apply { text = ""; textSize = 8f })
    }

    companion object {
        private const val UI_PREFS = "main_ui_prefs"
        private const val KEY_GBP_AMOUNT = "gbp_amount"
    }
}
