package com.example.gbpusdpredictor.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

// v5 -> v6: two changes together.
//
// 1. Every raw/derived market field is now nullable (Double?). Pre-v6, a failed live
// fetch fell back to a carried-forward prior value, or -- if there was no prior value at
// all -- a deliberately-fake sentinel (FETCH_FAILURE_SENTINEL). Both were a form of the
// same underlying problem: a row's `date` label implied every field on it was a genuine
// reading for that one date, when some fields could actually be a stand-in from a
// different day (or a plain fabrication) wearing that date's label. That's a real data
// leak into training -- the trees end up learning from rows that quietly mix a
// genuinely-fresh reading for one feature with a stale echo of a different day for
// another, all under one shared date. Per explicit request, there is no more standing in
// for a failed fetch: a field is either a real, date-verified reading, or it's null.
// TreeModelPredictor.kt already supports this natively (see its `missingGoesRight`
// per-split routing, inherited from ydf's own native missing-value handling) -- nothing
// on the inference side needed to change, only what MarketRepository is allowed to put
// in this table. carriedForwardFields is removed entirely -- there's nothing left to
// track once "real value or null" are the only two options.
//
// 2. Everything here is now built around ONE target date per refresh: the previous
// COMPLETE trading day (AppClock.previousBusinessDay()), not "today." A fixed
// same-instant refresh can never guarantee every source has published TODAY's figure yet
// (they publish on different schedules), which is exactly what caused fields from
// different real dates to get silently mixed under one row. The previous trading day, by
// contrast, is fully settled everywhere by the time a once-daily refresh runs -- see
// MarketRepository.refreshData() and MarketDataScheduler's 21:00 UTC anchor.
@Entity(tableName = "market_data", indices = [Index("date")])
data class MarketData(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // The trading day this row's fields describe -- AppClock.previousBusinessDay() as of
    // when the refresh ran, NOT the day the refresh itself ran on. See capturedAtUtc for
    // the latter.
    val date: String,
    // ISO-8601 instant (e.g. "2026-08-13T21:00:07.123Z") of the fetch that produced this
    // row -- when the refresh actually ran, as distinct from `date` (the trading day it
    // was fetching data FOR, always the day before).
    val capturedAtUtc: String,
    // BoE Bank Rate (series IUDBEDR), not a market-priced gilt yield -- see fetchUkRate's
    // comments in MarketRepository. Null if neither the GitHub gilt-yield snapshot nor a
    // direct BoE fetch had a reading whose OWN date matched `date` exactly.
    val ukRate: Double?,
    val us2y: Double?,
    // ukRate - us2y. Null whenever either operand is null -- never computed from a mix of
    // a real value and a carried-forward/fallback one.
    val spread2y: Double?,
    val gbpUsd: Double?,
    val oilPrice: Double?,
    val vix: Double?,
    val spread2yChange1d: Double?,
    val spread2yChange5d: Double?,
    val gbpUsdChange5d: Double?,
    val oilChange5d: Double?,
    val rsi14: Double?,
    // v6 -> v7: FX-cross momentum from Frankfurter (free, no key, same-day). All three are
    // 5-business-day % CHANGES, not raw levels -- safe to train on for the same reason
    // gbpUsdChange5d is (see this file's v5->v6 note and ml/README.md's leakage section).
    // gbpEurChange5d: sterling's own strength with the shared USD leg stripped out.
    // eurUsdChange5d: the deepest FX pair, GBP/USD's main co-mover.
    // usdBasketChange5d: mean 5d change of USD vs EUR/JPY/CAD/CHF (GBP deliberately left
    // out so it isn't circular with the target) -- "is this a dollar-wide move?".
    // Null follows the same rule as every other field: real, date-matched value or absent.
    val gbpEurChange5d: Double? = null,
    val eurUsdChange5d: Double? = null,
    val usdBasketChange5d: Double? = null,
    // v7 -> v8: realized GBP/USD volatility -- sample stdev of the last 10 daily returns,
    // computed on-device from the same Frankfurter history everything else uses (no extra
    // fetch). (An `evz` field for FRED's EVZCLS FX implied-vol index was added here too
    // and then removed: CBOE discontinued that series in March 2025, so it would have been
    // null on every row from then on.) Null follows the usual rule: real reading or absent.
    val gbpUsdRealVol10d: Double? = null,
    // v8 -> v9: two kinds of new feature, both zero-new-fetch.
    //
    // Tier A -- momentum companions derived from history already fetched every refresh for
    // us2y/vix/gbpUsd/the USD basket (no new network call): us2yChange5d, vixChange5d
    // (5-business-day % changes, same changeNDaysAgoFromSeries helper as the existing
    // *Change5d fields), gbpUsdChange1d and usdBasketChange1d (1-day companions to the
    // existing 5d versions).
    //
    // Tier B -- pure calendar, no fetch at all: dayOfWeek (1=Mon..5=Fri), monthOfYear
    // (1-12), isMonthEnd (1.0 if the next BUSINESS day rolls into a new calendar month,
    // else 0.0 -- deliberately business-day-based via AppClock.businessDaysForward, not
    // calendar month-end, to avoid reintroducing the calendar-vs-business-day train/serve
    // mismatch this project already found and fixed once -- see changeNDaysAgoFromSeries's
    // own doc comment in MarketRepository.kt).
    //
    // Null follows the usual rule: real value or absent, never a fabricated stand-in.
    val us2yChange5d: Double? = null,
    val vixChange5d: Double? = null,
    val gbpUsdChange1d: Double? = null,
    val usdBasketChange1d: Double? = null,
    val dayOfWeek: Double? = null,
    val monthOfYear: Double? = null,
    val isMonthEnd: Double? = null,
    // v9 -> v10: Brent crude 5-day % change, a second independent oil benchmark
    // alongside oilChange5d (WTI) -- not a replacement. Real comparison (2026-09)
    // showed Brent running $15-25/barrel above WTI and staying current through a
    // stretch where WTI's FRED series stalled for over a week; Brent is also the
    // benchmark UK/European fuel pricing tracks more closely. Same GitHub Action
    // (oil-fred-fetcher) and fetch/tolerance pattern as oilChange5d -- see
    // MarketRepository.fetchBrentHistoryFromGitHub. Null follows the usual rule.
    val brentChange5d: Double? = null,
    // v10 -> v11: longer-trend momentum companions, zero new fetches (same
    // gbpHistory/spreadSeries already fetched for the 1d/5d versions). Added because
    // the model only ever saw 1d/5d windows and kept calling stabilization during real,
    // sustained declines (three live misses Sep 21/28/30, all mid-decline) -- it had no
    // way to tell "still trending" from "noise" at a longer horizon. See
    // MarketRepository.refreshData()'s v10->v11 addition.
    val gbpUsdChange10d: Double? = null,
    val gbpUsdChange20d: Double? = null,
    val spread2yChange10d: Double? = null,
    // Legacy same-day estimate from the old hand-tuned linear formula. Kept only so old
    // rows don't break; no longer written by refreshData(). Prefer predicted14dRate below.
    @Deprecated("Superseded by predicted14dRate from the gradient-boosted-trees model")
    val prediction: Double? = null,
    // Output of the on-device tree model: predicted GBP/USD rate N trading days from
    // `date` (see MarketRepository.DEFAULT_HORIZON_DAYS). Null if gbpUsd itself is null
    // (nothing to scale a % change onto) or the model isn't loaded.
    val predicted14dRate: Double? = null,
    // Same forecast expressed as a % change vs. gbpUsd, for convenience in the UI/widget.
    val predicted14dChangePct: Double? = null
)

@Dao
interface MarketDao {
    // No onConflict strategy needed -- id autoGenerates a fresh, unique row every time.
    // In practice this table now gets exactly one row per trading day (refreshData()
    // checks getRowsForDate(targetDate) first and skips entirely if one already exists --
    // see its own comments), but the schema doesn't hard-enforce that uniqueness, on
    // purpose: a manual import/merge shouldn't be blocked by a conflict.
    @Insert
    suspend fun insert(data: MarketData): Long

    @Insert
    suspend fun insertAll(rows: List<MarketData>)

    // Used only to replace a recent row that was captured before all its sources had published
    // (see MarketRepository.captureDate): the replacement is written only if it is more complete.
    @Query("DELETE FROM market_data WHERE date = :date")
    suspend fun deleteForDate(date: String)

    @Query("SELECT * FROM market_data ORDER BY capturedAtUtc DESC LIMIT :limit")
    suspend fun getRecentEntries(limit: Int): List<MarketData>

    // Every row captured for one specific trading date. In normal operation this is 0 or
    // 1 rows (see the dedup note on insert() above) -- refreshData() calls this FIRST,
    // before any network fetch, to decide whether there's anything left to do for that
    // date at all.
    @Query("SELECT * FROM market_data WHERE date = :date ORDER BY capturedAtUtc DESC")
    suspend fun getRowsForDate(date: String): List<MarketData>

    @Query("SELECT * FROM market_data WHERE date = :date ORDER BY capturedAtUtc DESC LIMIT 1")
    suspend fun getLatestForDate(date: String): MarketData?

    // Recent daily rows, most-recent trading date first -- what feature-engineering code
    // (RSI, 5-day change, spread-history joins) and the chart/History screen read.
    @Query("SELECT * FROM market_data ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentDailyEntries(limit: Int): List<MarketData>

    // Most-recently-captured row overall -- what the home screen and widget show as "the
    // current reading." With one row per trading day, this is simply the most recent
    // trading date on record.
    @Query("SELECT * FROM market_data ORDER BY date DESC, capturedAtUtc DESC LIMIT 1")
    suspend fun getLatest(): MarketData?

    @Query("SELECT * FROM market_data ORDER BY date DESC, capturedAtUtc DESC")
    suspend fun getAllEntries(): List<MarketData>
}

// Every bootstrap-style fetch (one that reaches further back than "just today's reading"
// -- Frankfurter's historical range pull for RSI/momentum, FRED's per-series history
// pull, the gilt-yield GitHub snapshot's own rolling window) now writes a row here
// instead of running as an untracked side effect. Per explicit request: this makes
// bootstrap fetches inspectable after the fact -- whether a feature that "looks stuck"
// (e.g. oil unchanged for 5 days) is genuinely the source not publishing a new figure
// yet, versus the app simply not calling the fetch, is now a question you can answer by
// reading this table instead of guessing from Logcat.
@Entity(tableName = "bootstrap_fetch_log")
data class BootstrapFetchLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // When this bootstrap fetch actually ran.
    val loggedAtUtc: String,
    // Which fetch this was -- "frankfurter_price_history", "fred_us2y_history",
    // "fred_oil_history", "fred_vix_history", "gilt_yield_github", etc.
    val source: String,
    // What it was for -- "rsi14/gbpUsdChange5d bootstrap", "oilChange5d direct",
    // "spread2yChange1d/5d bootstrap", etc.
    val purpose: String,
    val requestedRangeStart: String?,
    val requestedRangeEnd: String?,
    // The specific date this refresh actually needed a reading for (usually
    // AppClock.previousBusinessDay()).
    val targetDate: String?,
    // How many real (date, value) observations came back in the response, total.
    val observationsReturned: Int,
    // The most recent date among those observations -- compare against targetDate to see
    // at a glance whether the source is caught up or lagging.
    val newestObservationDate: String?,
    val newestValue: Double?,
    // True only if one of the returned observations' OWN date exactly equalled
    // targetDate -- i.e. this fetch actually satisfied what the refresh needed, not just
    // "returned something."
    val matchedTargetDate: Boolean,
    // "OK", "NO_MATCH" (source responded but had nothing for targetDate -- e.g. not
    // published yet), "EMPTY" (source returned zero observations), or "ERROR: <message>".
    val outcome: String
)

@Dao
interface BootstrapLogDao {
    @Insert
    suspend fun insert(entry: BootstrapFetchLog)

    @Query("SELECT * FROM bootstrap_fetch_log ORDER BY loggedAtUtc DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<BootstrapFetchLog>
}
