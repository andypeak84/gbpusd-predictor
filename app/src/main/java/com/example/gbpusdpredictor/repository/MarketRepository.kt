package com.example.gbpusdpredictor.repository

import com.example.gbpusdpredictor.data.AppDatabase
import com.example.gbpusdpredictor.data.BootstrapFetchLog
import com.example.gbpusdpredictor.data.MarketData
import com.example.gbpusdpredictor.ml.TreeModelPredictor
import com.example.gbpusdpredictor.network.MacroApi
import com.example.gbpusdpredictor.network.BoeApi
import com.example.gbpusdpredictor.network.FrankfurterService
import com.example.gbpusdpredictor.network.GiltYieldApi
import com.example.gbpusdpredictor.utils.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.util.Log

/**
 * SUCCESS: captured a fresh row for AppClock.previousBusinessDay() (some fields may
 * still be null -- see MarketData.kt's v5->v6 note; a successful refresh no longer
 * implies every field was fetched).
 * SKIPPED_ALREADY_CAPTURED: that trading date already has a row -- covers both "already
 * ran today" AND weekends (a Saturday/Sunday run computes the same target date as
 * Friday's run already captured), with no separate day-of-week check needed.
 * FAILED: either something threw before a row could be written at all (e.g. couldn't
 * reach the database), OR all four core fields (gbpUsd, us2y, ukRate, vix) came back
 * empty for the target date -- in which case the row is deliberately NOT written and
 * FAILED is returned so the worker retries, rather than freezing that date as an
 * all-null row (see refreshData's own comment on the corePresent == 0 check). A SINGLE
 * field's fetch failing is still NOT a FAILED refresh -- that field is just null on an
 * otherwise-successful row.
 */
enum class RefreshResult { SUCCESS, SKIPPED_ALREADY_CAPTURED, FAILED }

class MarketRepository(
    private val database: AppDatabase,
    private val frankfurterService: FrankfurterService,
    private val treeModel: TreeModelPredictor
) {
    companion object {
        private const val BOE_RATE_SERIES_CODE = "IUDBEDR"
        private const val RSI_PERIOD = 14
        // Window for gbpUsdRealVol10d: sample stdev of the last 10 daily GBP/USD returns.
        private const val REALIZED_VOL_WINDOW = 10
        private const val HISTORY_LOOKBACK_DAYS = 40L

        // How far back the target date sits, in business days. Every source must have a
        // real, exact-date-matched value at THIS SAME date -- so this has to be set by
        // whichever source is slowest, not by gbpUsd's own (much faster) availability.
        // Was 3 (set by FRED's old DCOILWTICO oil series, observed 2 business days behind
        // a 1-day-back target = 3 total). Oil's raw LEVEL is out of this calculation --
        // it's dropped entirely (only oilChange5d is populated now, via a GitHub Action --
        // see fetchOilHistoryFromGitHub) and that fetch's own SMALL anchor tolerance
        // (changeNDaysAgoFromSeries's anchorToleranceDays=1, oil's call site only) absorbs
        // its ordinary single-day lag independently, so it doesn't feed back into this
        // shared constant. That tolerance is deliberately narrow -- it covers the routine
        // one-business-day miss (e.g. a holiday) confirmed against oil's real 2026
        // publish history, not a genuine multi-day source outage, which should (and does)
        // still come back null rather than pairing a stale oil reading with every OTHER
        // field's genuinely-current one under the same date label. This stayed
        // at 2, set by the next-slowest of the fields that DO need an exact match:
        // ukRate/vix/us2y each sat 1 business day behind a 1-day-back target = 2 total,
        // the day this was measured.
        // CAVEAT: the 2 here is still reasoned from that single originally-observed day
        // for ukRate/vix/us2y specifically, not yet reconfirmed since. Check
        // bootstrap_fetch_log after a few more real refreshes if you want to be sure. If
        // any source is lagging past 2 days back, raise this; it's a single constant
        // specifically so that's a one-line change.
        // Public so MainActivity's staleness check can measure the newest stored row
        // against the date a refresh should have reached by now, using the same lag.
        const val TARGET_LAG_BUSINESS_DAYS = 2

        // "It will predict the following day" -- the app's single daily refresh captures
        // AppClock.previousBusinessDay()'s fully-settled data, and daysAhead=1 from that
        // baseline lands on the day the refresh itself runs. This is the number shown on
        // the widget/home screen by default; the in-app selector can still ask for any
        // horizon 1-7 from the same trained model.
        const val DEFAULT_HORIZON_DAYS = 1

        /**
         * Rebuilds the exact feature map refreshData() would have built, from an already-
         * stored row. A null field is simply OMITTED from the map -- TreeModelPredictor.kt
         * already routes a missing key through each split's trained missingGoesRight
         * direction (ydf's own native missing-value handling), so there is no fallback
         * value to invent here. Lets the UI recompute a prediction at a different horizon
         * instantly, with no new network fetch.
         */
        fun featuresFrom(data: MarketData, daysAheadOverride: Int): Map<String, Double> {
            val features = mutableMapOf<String, Double>()
            data.us2y?.let { features["us2y"] = it }
            data.gbpUsd?.let { features["gbpUsd"] = it }
            data.oilPrice?.let { features["oilPrice"] = it }
            data.vix?.let { features["vix"] = it }
            data.gbpUsdChange5d?.let { features["gbpUsdChange5d"] = it }
            data.oilChange5d?.let { features["oilChange5d"] = it }
            data.brentChange5d?.let { features["brentChange5d"] = it }
            data.gbpUsdChange10d?.let { features["gbpUsdChange10d"] = it }
            data.gbpUsdChange20d?.let { features["gbpUsdChange20d"] = it }
            data.spread2yChange10d?.let { features["spread2yChange10d"] = it }
            data.rsi14?.let { features["rsi14"] = it }
            data.ukRate?.let { features["ukRate"] = it }
            data.spread2y?.let { features["spread2y"] = it }
            data.spread2yChange1d?.let { features["spread2yChange1d"] = it }
            data.spread2yChange5d?.let { features["spread2yChange5d"] = it }
            // FX-cross momentum, all from Frankfurter (free, no key, same-day, no publish
            // lag). Safe to train on -- they're 5-business-day % CHANGES, not raw levels,
            // so they don't carry the cross-regime "this level -> bad things" leakage that
            // got gbpUsd's and spread2y's raw levels flagged (see ml/README.md). Emitted
            // here unconditionally now; a model trained before they existed just never
            // references the keys (TreeModelPredictor looks up by name), and a retrain that
            // adds them finds the app already supplying them.
            data.gbpEurChange5d?.let { features["gbpEurChange5d"] = it }
            data.eurUsdChange5d?.let { features["eurUsdChange5d"] = it }
            data.usdBasketChange5d?.let { features["usdBasketChange5d"] = it }
            // Realized GBP/USD volatility, computed on-device from the Frankfurter history.
            data.gbpUsdRealVol10d?.let { features["gbpUsdRealVol10d"] = it }
            // Zero-new-fetch momentum companions + pure calendar features (see MarketData
            // v8->v9 note).
            data.us2yChange5d?.let { features["us2yChange5d"] = it }
            data.vixChange5d?.let { features["vixChange5d"] = it }
            data.gbpUsdChange1d?.let { features["gbpUsdChange1d"] = it }
            data.usdBasketChange1d?.let { features["usdBasketChange1d"] = it }
            data.dayOfWeek?.let { features["dayOfWeek"] = it }
            data.monthOfYear?.let { features["monthOfYear"] = it }
            data.isMonthEnd?.let { features["isMonthEnd"] = it }
            features["daysAhead"] = daysAheadOverride.toDouble()
            return features
        }
    }

    private data class GiltYieldEntry(val date: String, val yield_2y: Double)
    private data class OilPriceEntry(val date: String, val price: Double)
    private val gson = com.google.gson.Gson()

    /** A handful of fetches have been observed failing with a DNS resolution error
     * (UnknownHostException) that clears up within seconds -- Android sometimes reports
     * NetworkType.CONNECTED (satisfying WorkManager's own constraint) a moment before DNS
     * is actually functional, e.g. right after a WiFi handoff or radio wake-up. Confirmed
     * via a real report: Frankfurter's call (first in refreshData()) succeeded while
     * every FRED/GitHub call right after it failed with "Unable to resolve host" within
     * the same refresh, seconds apart -- exactly that shape of blip, not a genuine outage.
     * Retries a bounded few times with a growing delay so a momentary blip like that
     * doesn't get baked in as a whole day's worth of null macro features when the very
     * same call would have succeeded moments later. Retries IOException (covers
     * UnknownHostException, SocketTimeoutException, etc.) AND transient HTTP status codes
     * -- 429 (rate limited) and 502/503/504 (gateway/overload), which a source can return
     * for a few seconds under load and then serve the same request fine. A 4xx other than
     * 429, and any parsing failure, is still left alone -- retrying those wouldn't help.
     */
    private fun isTransientHttp(e: Throwable): Boolean =
        e is retrofit2.HttpException && e.code().let { it == 429 || it == 502 || it == 503 || it == 504 }

    private suspend fun <T> retryNetwork(times: Int = 3, initialDelayMs: Long = 2000, block: suspend () -> T): T {
        var lastError: Exception? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                if (e !is java.io.IOException && !isTransientHttp(e)) throw e
                lastError = e
                if (attempt < times - 1) {
                    Log.w("MarketRepository", "retryNetwork: attempt ${attempt + 1}/$times failed (${e.message}), retrying...")
                    kotlinx.coroutines.delay(initialDelayMs * (attempt + 1))
                }
            }
        }
        throw lastError!!
    }

    suspend fun refreshData(): RefreshResult = withContext(Dispatchers.IO) {
        try {
            val targetDate = AppClock.businessDaysBack(AppClock.today(), TARGET_LAG_BUSINESS_DAYS)
            val targetDateStr = targetDate.toString()

            // One row per trading day, ever. If this date's already captured -- by an
            // earlier run today, or any earlier run at all -- there's nothing left to do.
            // This is also the entire weekend story now: a refresh that happens to run on
            // a Saturday/Sunday computes the same previousBusinessDay() Friday's run
            // already captured, finds that row, and skips. No day-of-week branching
            // needed anywhere.
            val existing = database.marketDao().getRowsForDate(targetDateStr)
            if (existing.isNotEmpty()) {
                Log.i("MarketRepository", "refreshData: $targetDateStr already captured (at ${existing.first().capturedAtUtc}) -- nothing to do.")
                return@withContext RefreshResult.SKIPPED_ALREADY_CAPTURED
            }

            // Every field below is fetched as a full recent HISTORY (not just "latest"),
            // and only the entry whose OWN reported date exactly equals targetDate is
            // ever used. A source that hasn't published targetDate's figure yet, or whose
            // most recent entry is for a different date, contributes null -- never a
            // different day's value wearing targetDate's label, and never a fabricated
            // placeholder. Each history fetch is logged to bootstrap_fetch_log (see
            // logBootstrap) so staleness in any source is something you can read directly
            // off that table instead of inferring it.

            val gbpHistory = fetchFrankfurterHistory(targetDate, purpose = "gbpUsd / gbpUsdChange5d / rsi14")
            val gbpUsd = gbpHistory[targetDate]
            val gbpUsdChange5d = changeNDaysAgoFromSeries(gbpHistory, targetDate, 5, toleranceDays = 2)
            // Zero-new-fetch momentum companion -- same gbpHistory already fetched above.
            val gbpUsdChange1d = changeNDaysAgoFromSeries(gbpHistory, targetDate, 1, toleranceDays = 1)
            // Longer-trend companions -- same gbpHistory, no new fetch. Added specifically
            // because the model only ever saw 1d/5d windows and kept calling stabilization
            // during real, sustained declines (e.g. three live misses Sep 21/28/30, all
            // mid-decline) -- it had no way to distinguish "still trending" from "noise" at
            // a longer horizon. HISTORY_LOOKBACK_DAYS=40 comfortably covers a 20-business-day
            // reference point (~28 calendar days) plus tolerance margin.
            val gbpUsdChange10d = changeNDaysAgoFromSeries(gbpHistory, targetDate, 10, toleranceDays = 2)
            val gbpUsdChange20d = changeNDaysAgoFromSeries(gbpHistory, targetDate, 20, toleranceDays = 2)
            val pricesUpToTarget = gbpHistory.filterKeys { it <= targetDate }.toSortedMap().values.toList()
            val rsi14 = run {
                // Require genuinely enough real points (period + 1) before computing --
                // calculateRSI's own internal "not enough data" branch returns a flat
                // 100.0, which is exactly the kind of fabricated stand-in this redesign
                // is meant to eliminate, so that branch is deliberately never reached here.
                if (gbpUsd != null && pricesUpToTarget.size > RSI_PERIOD) calculateRSI(pricesUpToTarget, RSI_PERIOD) else null
            }
            // Realized GBP/USD vol -- sample stdev of the last REALIZED_VOL_WINDOW daily
            // returns, from the SAME Frankfurter history (no extra fetch). Same "enough
            // real data or null" rule as rsi14 above.
            val gbpUsdRealVol10d =
                if (gbpUsd != null && pricesUpToTarget.size > REALIZED_VOL_WINDOW)
                    realizedVol(pricesUpToTarget, REALIZED_VOL_WINDOW)
                else null

            val us2yHistory = fetchMacroHistory("us2y", targetDate, purpose = "us2y")
            val us2y = us2yHistory[targetDate]
            // Zero-new-fetch momentum companion -- same us2yHistory already fetched above.
            val us2yChange5d = changeNDaysAgoFromSeries(us2yHistory, targetDate, 5, toleranceDays = 2)

            val vixHistory = fetchMacroHistory("vix", targetDate, purpose = "vix")
            val vix = vixHistory[targetDate]
            // Zero-new-fetch momentum companion -- same vixHistory already fetched above.
            val vixChange5d = changeNDaysAgoFromSeries(vixHistory, targetDate, 5, toleranceDays = 2)

            // Only oilChange5d comes back from this -- see fetchOilHistoryFromGitHub's own
            // comment for why the raw level stays dropped. changeNDaysAgoFromSeries's own
            // 2-day tolerance absorbs whatever lag the GitHub Action's own last successful
            // run has, independent of TARGET_LAG_BUSINESS_DAYS.
            val oilHistoryForChangeOnly = fetchOilHistoryFromGitHub(targetDate)
            // anchorToleranceDays=1: oil is the one source that occasionally misses
            // targetDate by exactly one business day (e.g. a holiday) even when otherwise
            // healthy -- see changeNDaysAgoFromSeries's own doc comment for why this is
            // deliberately small and oil-specific, not a general fix for a genuine
            // multi-day source outage (which should, correctly, still come back null).
            val oilChange5d = changeNDaysAgoFromSeries(oilHistoryForChangeOnly, targetDate, 5, toleranceDays = 2, anchorToleranceDays = 1)

            // Brent -- a second, independent oil benchmark alongside WTI (not a
            // replacement). See fetchBrentHistoryFromGitHub's own comment for why; same
            // fetch/tolerance pattern as oilChange5d exactly.
            val brentHistoryForChangeOnly = fetchBrentHistoryFromGitHub(targetDate)
            val brentChange5d = changeNDaysAgoFromSeries(brentHistoryForChangeOnly, targetDate, 5, toleranceDays = 2, anchorToleranceDays = 1)

            val giltHistory = fetchGiltYieldHistory(targetDate)
            // fetchUkRateForDate (direct BoE, Bank Rate not gilt yield -- see its own
            // comment) is a real date-matched fetch in its own right, not a bootstrap --
            // only tried if the GitHub gilt-yield snapshot didn't have targetDate. No more
            // falling back further than that to a fixed hand-maintained constant: a
            // manually-updated Bank Rate figure isn't date-matched to targetDate any more
            // than a carried-forward value was, so per this redesign it's null instead.
            val ukRate = giltHistory[targetDate] ?: fetchUkRateForDate(targetDate)

            val spread2y = if (ukRate != null && us2y != null) ukRate - us2y else null
            val spreadSeries = joinSpreadSeries(giltHistory, us2yHistory, toleranceDays = 3)
            val spread2yChange1d = changeNDaysAgoFromSeries(spreadSeries, targetDate, 1, toleranceDays = 1)
            val spread2yChange5d = changeNDaysAgoFromSeries(spreadSeries, targetDate, 5, toleranceDays = 2)
            // Longer-trend companion -- same spreadSeries, no new fetch. The Fed/BoE rate
            // divergence (US hiking, UK holding) showed up as spread2y trending wider over
            // weeks, not something a 1d/5d window captures well.
            val spread2yChange10d = changeNDaysAgoFromSeries(spreadSeries, targetDate, 10, toleranceDays = 2)

            // FX-cross momentum -- all Frankfurter, all 5-business-day % CHANGES (never a
            // raw level), same changeNDaysAgoFromSeries + 2-day tolerance as the other
            // *Change5d features. Frankfurter FX is same-day with no publish lag, so these
            // are the most reliably-present of any feature the model gets.
            val gbpEurHistory = fetchFrankfurterHistory(targetDate, purpose = "gbpEurChange5d", base = "GBP", symbol = "EUR")
            val gbpEurChange5d = changeNDaysAgoFromSeries(gbpEurHistory, targetDate, 5, toleranceDays = 2)

            val eurUsdHistory = fetchFrankfurterHistory(targetDate, purpose = "eurUsdChange5d", base = "EUR", symbol = "USD")
            val eurUsdChange5d = changeNDaysAgoFromSeries(eurUsdHistory, targetDate, 5, toleranceDays = 2)

            // USD vs the majors EXCLUDING GBP (kept GBP-free so it isn't circular with the
            // GBP/USD target). Average the 5-day change of USD against each leg -- they all
            // move together when the dollar strengthens broadly. Null unless at least half
            // the legs produced a real change.
            val usdBasketSymbols = listOf("EUR", "JPY", "CAD", "CHF")
            val usdBasketHistory = fetchFrankfurterBasketHistory(targetDate, base = "USD", symbols = usdBasketSymbols, purpose = "usdBasketChange5d")
            val usdBasketLegChanges = usdBasketSymbols.mapNotNull { sym ->
                usdBasketHistory[sym]?.let { changeNDaysAgoFromSeries(it, targetDate, 5, toleranceDays = 2) }
            }
            val usdBasketChange5d = if (usdBasketLegChanges.size >= 2) usdBasketLegChanges.average() else null
            // Zero-new-fetch momentum companion -- same usdBasketHistory already fetched
            // above, same >=2-leg guard as the 5d version.
            val usdBasketLegChanges1d = usdBasketSymbols.mapNotNull { sym ->
                usdBasketHistory[sym]?.let { changeNDaysAgoFromSeries(it, targetDate, 1, toleranceDays = 1) }
            }
            val usdBasketChange1d = if (usdBasketLegChanges1d.size >= 2) usdBasketLegChanges1d.average() else null

            // Tier B -- pure calendar, no fetch at all. isMonthEnd deliberately uses the
            // BUSINESS-day definition (AppClock.businessDaysForward), not calendar
            // month-end, matching this project's established business-day-only convention
            // (see changeNDaysAgoFromSeries's own doc comment on why calendar-day stepping
            // was a real train/serve bug here once already).
            val dayOfWeek = targetDate.dayOfWeek.value.toDouble()
            val monthOfYear = targetDate.monthValue.toDouble()
            val isMonthEnd = if (AppClock.businessDaysForward(targetDate, 1).monthValue != targetDate.monthValue) 1.0 else 0.0

            val marketData = MarketData(
                date = targetDateStr,
                capturedAtUtc = Instant.now().toString(),
                ukRate = ukRate,
                us2y = us2y,
                spread2y = spread2y,
                gbpUsd = gbpUsd,
                // oilPrice (the raw LEVEL) stays permanently null -- see
                // fetchOilHistoryFromGitHub's own comment for the full history of why.
                // Only the 5-day CHANGE is populated now, computed above.
                oilPrice = null,
                vix = vix,
                spread2yChange1d = spread2yChange1d,
                spread2yChange5d = spread2yChange5d,
                spread2yChange10d = spread2yChange10d,
                gbpUsdChange5d = gbpUsdChange5d,
                oilChange5d = oilChange5d,
                rsi14 = rsi14,
                gbpEurChange5d = gbpEurChange5d,
                eurUsdChange5d = eurUsdChange5d,
                usdBasketChange5d = usdBasketChange5d,
                gbpUsdRealVol10d = gbpUsdRealVol10d,
                us2yChange5d = us2yChange5d,
                vixChange5d = vixChange5d,
                gbpUsdChange1d = gbpUsdChange1d,
                usdBasketChange1d = usdBasketChange1d,
                dayOfWeek = dayOfWeek,
                monthOfYear = monthOfYear,
                isMonthEnd = isMonthEnd,
                brentChange5d = brentChange5d,
                gbpUsdChange10d = gbpUsdChange10d,
                gbpUsdChange20d = gbpUsdChange20d
            )

            val corePresent = listOf(gbpUsd, us2y, ukRate, vix).count { it != null }
            Log.i(
                "MarketRepository",
                "refreshData: captured $targetDateStr -- $corePresent/4 core field(s) present " +
                    "(gbpUsd=${gbpUsd != null}, us2y=${us2y != null}, ukRate=${ukRate != null}, " +
                    "vix=${vix != null})"
            )

            // Every core source came back empty for targetDate. Do NOT write this row:
            // once any row exists for a date, the getRowsForDate() dedup check at the top
            // of refreshData() treats that date as permanently captured and never re-fetches
            // it -- so an all-null row written during a transient total outage (the DNS-blip
            // scenario retryNetwork() describes, hitting every call at once) would freeze
            // that trading day as empty forever, even after the sources publish. Returning
            // FAILED instead lets MarketDataWorker retry on its own backoff. A genuine
            // no-trading-data day (a shared US/UK bank holiday) will legitimately exhaust
            // those retries and end with no row at all -- which is the honest result for a
            // day with no real data, not a regression from a row full of "null".
            if (corePresent == 0) {
                Log.w("MarketRepository", "refreshData: 0/4 core fields for $targetDateStr -- not writing a row, returning FAILED for retry.")
                return@withContext RefreshResult.FAILED
            }

            // Missing features are simply omitted from the map (see featuresFrom) and
            // routed per-split via the model's own trained missing-value handling -- a
            // prediction is still attempted even with some fields null. Only gbpUsd itself
            // being null blocks turning a % change into an absolute predicted RATE (there's
            // nothing to scale it onto); the % change prediction itself can still be
            // computed and stored.
            val predictedChangePct = treeModel.predictChangePct(featuresFrom(marketData, DEFAULT_HORIZON_DAYS))
            val predicted14dRate = if (predictedChangePct != null && gbpUsd != null) gbpUsd * (1.0 + predictedChangePct) else null
            if (predictedChangePct == null) {
                Log.w("MarketRepository", "No forecast: tree model unavailable (see TreeModelPredictor logs)")
            }

            database.marketDao().insert(marketData.copy(predicted14dRate = predicted14dRate, predicted14dChangePct = predictedChangePct))

            RefreshResult.SUCCESS
        } catch (e: Exception) {
            Log.e("MarketRepository", "refreshData failed", e)
            RefreshResult.FAILED
        }
    }

    // --- Bootstrap-style history fetches, each logged to bootstrap_fetch_log ----------

    /** Records one bootstrap-style history fetch so it's inspectable after the fact --
     * see BootstrapFetchLog's own doc comment for why this exists at all. */
    private suspend fun logBootstrap(
        source: String,
        purpose: String,
        requestedRangeStart: String?,
        requestedRangeEnd: String?,
        targetDate: LocalDate,
        observations: Map<LocalDate, Double>,
        outcome: String
    ) {
        val newestDate = observations.keys.maxOrNull()
        try {
            database.bootstrapLogDao().insert(
                BootstrapFetchLog(
                    loggedAtUtc = Instant.now().toString(),
                    source = source,
                    purpose = purpose,
                    requestedRangeStart = requestedRangeStart,
                    requestedRangeEnd = requestedRangeEnd,
                    targetDate = targetDate.toString(),
                    observationsReturned = observations.size,
                    newestObservationDate = newestDate?.toString(),
                    newestValue = newestDate?.let { observations[it] },
                    matchedTargetDate = observations.containsKey(targetDate),
                    outcome = outcome
                )
            )
        } catch (e: Exception) {
            Log.e("MarketRepository", "logBootstrap failed to write ($source)", e)
        }
    }

    /** US 2yr yield ("us2y") or VIX ("vix") history, read from the data files the developer's GitHub
     * Action publishes (MacroService) -- NOT from FRED directly. The old direct call needed a FRED API
     * key compiled into the APK, which for a public Google Play release anyone could extract; the key
     * now lives only in the Action's secret. Same [{"date","price"}] shape, parse and outcome logging
     * as fetchOilHistoryFromGitHub. Every call is logged via logBootstrap; the caller only ever reads the
     * exact targetDate entry (or a tolerance-bounded change) back out, never "whatever's most recent." */
    private suspend fun fetchMacroHistory(file: String, targetDate: LocalDate, purpose: String): Map<LocalDate, Double> {
        val source = "macro_${file}_github"
        val raw = try {
            retryNetwork {
                if (file == "us2y") MacroApi.service.getUs2yRaw() else MacroApi.service.getVixRaw()
            }
        } catch (e: Exception) {
            Log.e("MarketRepository", "fetchMacroHistory($file) failed", e)
            logBootstrap(source, purpose, null, null, targetDate, emptyMap(), "ERROR: ${e.message}")
            return emptyMap()
        }
        val map: Map<LocalDate, Double> = try {
            @Suppress("DEPRECATION") // see fetchOilHistoryFromGitHub: JsonParser().parse() works across more Gson versions.
            val root = com.google.gson.JsonParser().parse(raw)
            if (!root.isJsonArray) emptyMap() else root.asJsonArray.mapNotNull { el ->
                try {
                    val entry = gson.fromJson(el, OilPriceEntry::class.java)
                    LocalDate.parse(entry.date) to entry.price
                } catch (e: Exception) { null }
            }.toMap()
        } catch (e: Exception) {
            Log.w("MarketRepository", "fetchMacroHistory($file): couldn't parse response -- started with: ${raw.take(80)}", e)
            emptyMap()
        }
        val outcome = when {
            map.isEmpty() -> "EMPTY"
            map.containsKey(targetDate) -> "OK"
            else -> {
                val newest = map.keys.maxOrNull()
                val staleness = newest?.let { AppClock.businessDaysBetween(it, targetDate) }
                if (staleness != null) "NO_MATCH (source $staleness business day${if (staleness == 1) "" else "s"} stale)" else "NO_MATCH"
            }
        }
        logBootstrap(source, purpose, null, null, targetDate, map, outcome)
        return map
    }

    /** Recreated -- see this class's own history: FRED direct (worked, but the raw LEVEL
     * lagged 2 business days behind target), API Ninjas (abandoned -- premium-gated for
     * crude oil on both its historical AND current-price endpoints, confirmed via real
     * HTTP 400s with actual error bodies), and now this: a GitHub Action
     * (oil-fred-fetcher) that pulls FRED's DCOILWTICO fresh each run and writes it here,
     * read back the same way gilt yield already is. Only ever used to compute
     * oilChange5d -- a % CHANGE, not the raw price level, same reasoning as before (the
     * project's established pattern: raw levels are the leakage-prone ones, see gbpUsd's
     * own level being dropped entirely). Real advantage over the abandoned API Ninjas
     * version: FRED has a genuine historical archive, so the Action re-fetches a fresh
     * window every run rather than accumulating day by day -- no cold-start wait, and a
     * missed run self-heals on the next successful one instead of a permanent gap. */
    private suspend fun fetchOilHistoryFromGitHub(targetDate: LocalDate): Map<LocalDate, Double> {
        val source = "oil_price_github_fred"
        val purpose = "oilChange5d only (no raw price stored)"
        val raw = try {
            retryNetwork { com.example.gbpusdpredictor.network.OilPriceApi.service.getLatestRaw() }
        } catch (e: Exception) {
            Log.e("MarketRepository", "fetchOilHistoryFromGitHub failed", e)
            logBootstrap(source, purpose, null, null, targetDate, emptyMap(), "ERROR: ${e.message}")
            return emptyMap()
        }
        val entries: List<OilPriceEntry> = try {
            @Suppress("DEPRECATION") // JsonParser().parse() works across a much wider range of
            // Gson versions than the newer static JsonParser.parseString().
            val root = com.google.gson.JsonParser().parse(raw)
            when {
                root.isJsonArray -> root.asJsonArray.mapNotNull { el ->
                    try { gson.fromJson(el, OilPriceEntry::class.java) } catch (e: Exception) { null }
                }
                root.isJsonObject -> listOfNotNull(
                    try { gson.fromJson(root, OilPriceEntry::class.java) } catch (e: Exception) { null }
                )
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.w("MarketRepository", "fetchOilHistoryFromGitHub: couldn't parse response -- raw content started with: ${raw.take(80)}", e)
            emptyList()
        }
        val historyByDate: Map<LocalDate, Double> = entries.mapNotNull { entry ->
            val parsedDate = try { LocalDate.parse(entry.date) } catch (e: Exception) { null }
            parsedDate?.let { it to entry.price }
        }.toMap()
        val outcome = when {
            historyByDate.isEmpty() -> "EMPTY"
            historyByDate.containsKey(targetDate) -> "OK"
            else -> {
                // Folds how stale the SOURCE itself is into the logged outcome, so a real
                // upstream outage (e.g. FRED not publishing DCOILWTICO for days) reads as
                // "source N business days stale" at a glance on the Bootstrap Log screen,
                // rather than a bare NO_MATCH indistinguishable from a plain fetch miss.
                val newestDate = historyByDate.keys.maxOrNull()
                val staleness = newestDate?.let { AppClock.businessDaysBetween(it, targetDate) }
                if (staleness != null) {
                    "NO_MATCH (source $staleness business day${if (staleness == 1) "" else "s"} stale)"
                } else {
                    "NO_MATCH"
                }
            }
        }
        logBootstrap(source, purpose, null, null, targetDate, historyByDate, outcome)
        return historyByDate
    }

    /** Brent crude -- a second, independent oil benchmark alongside WTI, same GitHub
     * Action (oil-fred-fetcher) and fetch/tolerance pattern as fetchOilHistoryFromGitHub
     * exactly, just reading data/brent-price.json via BrentPriceApi instead of
     * data/oil-price.json. Not a replacement for WTI: Brent is the global benchmark
     * UK/European fuel pricing actually tracks more closely, and a real comparison
     * (2026-09) showed Brent running $15-25/barrel above WTI while staying current
     * through a stretch where WTI's FRED series stalled for over a week -- the two carry
     * different information (regional/geopolitical risk premiums show up in Brent
     * first; US-specific supply/storage dynamics in WTI), so both are kept. Only ever
     * used to compute brentChange5d -- a % CHANGE, not the raw price level, same
     * leakage-avoidance reasoning as oilChange5d. */
    private suspend fun fetchBrentHistoryFromGitHub(targetDate: LocalDate): Map<LocalDate, Double> {
        val source = "brent_price_github_fred"
        val purpose = "brentChange5d only (no raw price stored)"
        val raw = try {
            retryNetwork { com.example.gbpusdpredictor.network.BrentPriceApi.service.getLatestRaw() }
        } catch (e: Exception) {
            Log.e("MarketRepository", "fetchBrentHistoryFromGitHub failed", e)
            logBootstrap(source, purpose, null, null, targetDate, emptyMap(), "ERROR: ${e.message}")
            return emptyMap()
        }
        val entries: List<OilPriceEntry> = try {
            @Suppress("DEPRECATION") // JsonParser().parse() works across a much wider range of
            // Gson versions than the newer static JsonParser.parseString().
            val root = com.google.gson.JsonParser().parse(raw)
            when {
                root.isJsonArray -> root.asJsonArray.mapNotNull { el ->
                    try { gson.fromJson(el, OilPriceEntry::class.java) } catch (e: Exception) { null }
                }
                root.isJsonObject -> listOfNotNull(
                    try { gson.fromJson(root, OilPriceEntry::class.java) } catch (e: Exception) { null }
                )
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.w("MarketRepository", "fetchBrentHistoryFromGitHub: couldn't parse response -- raw content started with: ${raw.take(80)}", e)
            emptyList()
        }
        val historyByDate: Map<LocalDate, Double> = entries.mapNotNull { entry ->
            val parsedDate = try { LocalDate.parse(entry.date) } catch (e: Exception) { null }
            parsedDate?.let { it to entry.price }
        }.toMap()
        val outcome = when {
            historyByDate.isEmpty() -> "EMPTY"
            historyByDate.containsKey(targetDate) -> "OK"
            else -> {
                val newestDate = historyByDate.keys.maxOrNull()
                val staleness = newestDate?.let { AppClock.businessDaysBetween(it, targetDate) }
                if (staleness != null) {
                    "NO_MATCH (source $staleness business day${if (staleness == 1) "" else "s"} stale)"
                } else {
                    "NO_MATCH"
                }
            }
        }
        logBootstrap(source, purpose, null, null, targetDate, historyByDate, outcome)
        return historyByDate
    }


    /** Frankfurter's historical range endpoint, bounded to [targetDate - lookback,
     * targetDate]. The GBP/USD call (the default) feeds gbpUsd, gbpUsdChange5d and rsi14
     * from ONE fetch so they can never disagree about which day they describe; the same
     * helper also pulls GBP/EUR and EUR/USD for the FX-cross momentum features. `base`
     * and `symbol` are ISO currency codes; the returned map is date -> that pair's rate.
     * Logged via logBootstrap. */
    private suspend fun fetchFrankfurterHistory(
        targetDate: LocalDate,
        purpose: String,
        base: String = "GBP",
        symbol: String = "USD"
    ): Map<LocalDate, Double> {
        val source = "frankfurter_${base}${symbol}_history"
        val start = targetDate.minusDays(HISTORY_LOOKBACK_DAYS)
        val rangeKey = "$start..$targetDate"
        return try {
            val response = retryNetwork { frankfurterService.getTimeSeries(rangeKey, base = base, symbols = symbol) }
            val map = response.rates.mapNotNull { (dateStr, rates) ->
                val d = try { LocalDate.parse(dateStr) } catch (e: Exception) { return@mapNotNull null }
                val v = rates[symbol] ?: return@mapNotNull null
                d to v
            }.toMap()
            val outcome = when {
                map.isEmpty() -> "EMPTY"
                map.containsKey(targetDate) -> "OK"
                else -> "NO_MATCH"
            }
            logBootstrap(source, purpose, start.toString(), targetDate.toString(), targetDate, map, outcome)
            map
        } catch (e: Exception) {
            Log.e("MarketRepository", "fetchFrankfurterHistory($base/$symbol) failed", e)
            logBootstrap(source, purpose, start.toString(), targetDate.toString(), targetDate, emptyMap(), "ERROR: ${e.message}")
            emptyMap()
        }
    }

    /** One Frankfurter call for several symbols at once (base vs each of `symbols`),
     * returning symbol -> (date -> rate). Used only for the GBP-free USD basket
     * (usdBasketChange5d): fetching EUR/JPY/CAD/CHF against USD in a single request keeps
     * them all on the same fetch/date basis, same principle as the GBP/USD triple. */
    private suspend fun fetchFrankfurterBasketHistory(
        targetDate: LocalDate,
        base: String,
        symbols: List<String>,
        purpose: String
    ): Map<String, Map<LocalDate, Double>> {
        val source = "frankfurter_${base}basket_history"
        val start = targetDate.minusDays(HISTORY_LOOKBACK_DAYS)
        val rangeKey = "$start..$targetDate"
        return try {
            val response = retryNetwork {
                frankfurterService.getTimeSeries(rangeKey, base = base, symbols = symbols.joinToString(","))
            }
            val bySymbol: Map<String, Map<LocalDate, Double>> = symbols.associateWith { sym ->
                response.rates.mapNotNull { (dateStr, rates) ->
                    val d = try { LocalDate.parse(dateStr) } catch (e: Exception) { return@mapNotNull null }
                    val v = rates[sym] ?: return@mapNotNull null
                    d to v
                }.toMap()
            }
            val outcome = when {
                bySymbol.values.all { it.isEmpty() } -> "EMPTY"
                bySymbol.values.all { it.containsKey(targetDate) } -> "OK"
                bySymbol.values.any { it.containsKey(targetDate) } -> "PARTIAL"
                else -> "NO_MATCH"
            }
            val representative = bySymbol.values.firstOrNull { it.isNotEmpty() } ?: emptyMap()
            logBootstrap(source, purpose, start.toString(), targetDate.toString(), targetDate, representative, outcome)
            bySymbol
        } catch (e: Exception) {
            Log.e("MarketRepository", "fetchFrankfurterBasketHistory($base vs $symbols) failed", e)
            logBootstrap(source, purpose, start.toString(), targetDate.toString(), targetDate, emptyMap(), "ERROR: ${e.message}")
            emptyMap()
        }
    }

    /** The user's own free, self-maintained daily 2yr gilt-yield snapshot at
     * github.com/andypeak84/gilt-yield-fetcher -- holds a rolling ~16-day window (see
     * that repo's MAX_DAYS), handling the file being either a single {date, yield_2y}
     * object or an array of them. Logged via logBootstrap; the caller only ever reads the
     * exact targetDate entry back out. */
    private suspend fun fetchGiltYieldHistory(targetDate: LocalDate): Map<LocalDate, Double> {
        val source = "gilt_yield_github"
        val purpose = "ukRate / spread2y history"
        val raw = try {
            retryNetwork { GiltYieldApi.service.getLatestRaw() }
        } catch (e: Exception) {
            Log.e("MarketRepository", "fetchGiltYieldHistory failed", e)
            logBootstrap(source, purpose, null, null, targetDate, emptyMap(), "ERROR: ${e.message}")
            return emptyMap()
        }
        val entries: List<GiltYieldEntry> = try {
            @Suppress("DEPRECATION") // JsonParser().parse() works across a much wider range of
            // Gson versions than the newer static JsonParser.parseString().
            val root = com.google.gson.JsonParser().parse(raw)
            when {
                root.isJsonArray -> root.asJsonArray.mapNotNull { el ->
                    try { gson.fromJson(el, GiltYieldEntry::class.java) } catch (e: Exception) { null }
                }
                root.isJsonObject -> listOfNotNull(
                    try { gson.fromJson(root, GiltYieldEntry::class.java) } catch (e: Exception) { null }
                )
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.w("MarketRepository", "fetchGiltYieldHistory: couldn't parse response -- raw content started with: ${raw.take(80)}", e)
            emptyList()
        }
        val historyByDate: Map<LocalDate, Double> = entries.mapNotNull { entry ->
            val parsedDate = try { LocalDate.parse(entry.date) } catch (e: Exception) { null }
            parsedDate?.let { it to entry.yield_2y }
        }.toMap()
        val outcome = when {
            historyByDate.isEmpty() -> "EMPTY"
            historyByDate.containsKey(targetDate) -> "OK"
            else -> "NO_MATCH"
        }
        logBootstrap(source, purpose, null, null, targetDate, historyByDate, outcome)
        return historyByDate
    }

    // --- Direct (non-bootstrap) single-date fallback fetch ----------------------------

    /** Direct BoE fetch -- Bank Rate (series IUDBEDR), NOT a market-priced gilt yield.
     * BoE's true 2yr gilt yield comes off their fitted yield curve and is only published
     * as an Excel archive, not via any API -- Bank Rate is the closest fetchable UK
     * short-rate, tried only when the GitHub gilt-yield snapshot doesn't have targetDate.
     * Returns null (not a hand-maintained constant) if BoE doesn't have a row for
     * targetDate either -- see refreshData()'s comment on why the old fixed-constant
     * fallback was retired. */
    private suspend fun fetchUkRateForDate(targetDate: LocalDate): Double? = try {
        val formatter = DateTimeFormatter.ofPattern("dd/MMM/yyyy", Locale.ENGLISH)
        val from = targetDate.minusDays(7)
        val csv = retryNetwork {
            BoeApi.service.getSeriesCsv(
                dateFrom = from.format(formatter),
                dateTo = targetDate.format(formatter),
                seriesCodes = BOE_RATE_SERIES_CODE
            )
        }
        val trimmed = csv.trim()
        if (trimmed.startsWith("<") || trimmed.contains("Access denied", ignoreCase = true)) {
            Log.w("MarketRepository", "fetchUkRateForDate blocked (BoE bot/WAF protection)")
            null
        } else {
            val targetLabel = targetDate.format(formatter)
            val lines = trimmed.lines().filter { it.isNotBlank() && !it.startsWith("DATE", ignoreCase = true) }
            val match = lines.firstOrNull { it.split(",").getOrNull(0)?.trim()?.equals(targetLabel, ignoreCase = true) == true }
            val value = match?.split(",")?.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (value == null) {
                Log.w("MarketRepository", "fetchUkRateForDate: no BoE row for $targetDate (target label \"$targetLabel\").")
            }
            value
        }
    } catch (e: Exception) {
        Log.w("MarketRepository", "fetchUkRateForDate failed", e)
        null
    }

    // --- Pure helpers -------------------------------------------------------------------

    /** UK gilt-yield readings and US 2yr readings don't necessarily fall on exactly the
     * same dates (UK/US market holidays differ) -- pairs each UK reading with the nearest
     * US reading within toleranceDays to build a synthetic spread2y-over-time series. */
    private fun joinSpreadSeries(
        ukHistory: Map<LocalDate, Double>,
        usHistory: Map<LocalDate, Double>,
        toleranceDays: Int
    ): Map<LocalDate, Double> {
        if (usHistory.isEmpty()) return emptyMap()
        return ukHistory.mapNotNull { (ukDate, ukVal) ->
            val nearestUs = usHistory.entries
                .map { it to Math.abs(it.key.toEpochDay() - ukDate.toEpochDay()) }
                .minByOrNull { it.second }
                ?.takeIf { it.second <= toleranceDays }
                ?.first ?: return@mapNotNull null
            ukDate to (ukVal - nearestUs.value)
        }.toMap()
    }

    /** Nearest-within-tolerance % change vs. `daysAgo` BUSINESS days before `latestDate`
     * (via AppClock.businessDaysBack -- skips Saturday/Sunday, same as this class's own
     * targetDate calculation), read from an in-memory (date -> value) series. Returns null
     * (not a fallback value) if `latestDate` itself isn't in the series, or nothing within
     * tolerance is found for the reference point.
     *
     * Was calendar days (`latestDate.minusDays(daysAgo)`) until this was pointed out as a
     * real train/serve mismatch: train_model.py computes every *Change5d/1d feature via
     * pandas' `pct_change(N)` on a business-day-reindexed series -- N BUSINESS days back --
     * while this was stepping back N CALENDAR days. For a 5-day change targeting a
     * Wednesday, that's prior-Wednesday (5 business days) in training vs. prior-Friday (~3
     * business days) served live -- a materially different reference point wearing the
     * same "5d change" label, on every one of gbpUsdChange5d/oilChange5d/
     * spread2yChange1d/spread2yChange5d. Business-day step-back now matches training's
     * definition; toleranceDays still absorbs a source-specific holiday the business-day
     * target itself lands on (UK/US bank holidays differ from each other and from what a
     * given source actually publishes for).
     *
     * The reference search excludes the anchor date itself: with a small daysAgo/tolerance
     * (e.g. spread2yChange1d, daysAgo=1 tolerance=1) and a gap at the real reference date
     * -- e.g. a holiday the business-day step-back lands on -- the nearest remaining point
     * would otherwise BE the anchor date, distance `daysAgo` <= tolerance, yielding a
     * spurious exact 0.0 change that's indistinguishable from a genuine no-change reading.
     * Better to return null (feature absent) than a fabricated zero.
     *
     * `anchorToleranceDays` (default 0 -- exact match, same as before this parameter
     * existed): how many BUSINESS days (via AppClock.businessDaysBetween, not raw
     * calendar days) EARLIER than `latestDate` the anchor reading itself is allowed
     * to be, when `latestDate` has no real reading of its own. Business days, not
     * calendar days, matters here: a single Monday holiday makes the preceding
     * Friday's reading 3 CALENDAR days back but only 1 BUSINESS day back -- using
     * calendar-day distance would need tolerance>=3 to cover the exact same case
     * "1 business day" is meant to describe. Zero for every
     * caller except oilChange5d -- gbpUsd/us2y/vix/spread2y/the FX crosses all genuinely
     * publish close enough to `latestDate` that requiring an exact match is correct and
     * meaningful (a real, same-day reading, never a stale one wearing a different day's
     * label). Oil is the one source observed to occasionally miss `latestDate` by exactly
     * one business day (a holiday) even when it's otherwise perfectly healthy -- see
     * OilPriceService.kt's doc comment. NEVER allowed to pick a reading AFTER `latestDate`
     * (that would be using information not actually available as of `latestDate` -- a
     * look-ahead leak, not a lag tolerance). A large anchorToleranceDays would reintroduce
     * exactly the misaligned-timestamp problem this whole file was rewritten to eliminate
     * (see MarketData.kt's v5->v6 note) -- pairing a stale anchor with every OTHER field's
     * genuinely-current reading under one shared `latestDate` label. Keep this small and
     * source-specific, not a general escape hatch. */
    private fun changeNDaysAgoFromSeries(
        seriesByDate: Map<LocalDate, Double>,
        latestDate: LocalDate,
        daysAgo: Int,
        toleranceDays: Int,
        anchorToleranceDays: Int = 0
    ): Double? {
        val (anchorDate, latestValue) = seriesByDate.entries
            .filter { it.key <= latestDate }
            .map { it to AppClock.businessDaysBetween(it.key, latestDate) }
            .minByOrNull { it.second }
            ?.takeIf { it.second <= anchorToleranceDays }
            ?.let { it.first.key to it.first.value } ?: return null
        val targetDate = AppClock.businessDaysBack(anchorDate, daysAgo)
        val reference = seriesByDate.entries
            .filter { it.key < anchorDate }
            .map { it to Math.abs(it.key.toEpochDay() - targetDate.toEpochDay()) }
            .minByOrNull { it.second }
            ?.takeIf { it.second <= toleranceDays }
            ?.first ?: return null
        val refValue = reference.value
        if (refValue == 0.0) return null
        return (latestValue - refValue) / refValue
    }

    private fun calculateRSI(closesOldestFirst: List<Double>, period: Int): Double {
        val deltas = closesOldestFirst.zipWithNext { a, b -> b - a }
        val gains = deltas.map { if (it > 0) it else 0.0 }
        val losses = deltas.map { if (it < 0) -it else 0.0 }
        val avgGain = gains.takeLast(period).average()
        val avgLoss = losses.takeLast(period).average()
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    /** Sample standard deviation (ddof = 1, matching pandas' Series.std() default that
     * train_model.py uses) of the last `window` daily simple returns from an oldest-first
     * close series. Caller guarantees at least `window` + 1 closes. This is REALIZED
     * GBP/USD volatility -- there's no free live FX IMPLIED-vol series to pair it with
     * (FRED's EVZCLS was discontinued March 2025). */
    private fun realizedVol(closesOldestFirst: List<Double>, window: Int): Double? {
        val returns = closesOldestFirst.zipWithNext { a, b -> b / a - 1.0 }.takeLast(window)
        if (returns.size < window) return null
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1)
        return kotlin.math.sqrt(variance)
    }
}
