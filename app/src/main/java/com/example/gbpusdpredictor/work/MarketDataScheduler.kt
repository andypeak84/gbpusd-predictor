package com.example.gbpusdpredictor.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * One scheduled refresh a day -- full stop. No immediate on-open refresh, no manual
 * "Refresh Now" button; this is the only thing that ever writes a row.
 *
 * Deliberately NOT a PeriodicWorkRequest any more. PeriodicWorkRequestBuilder only
 * anchors its FIRST execution to setInitialDelay -- every run after that is scheduled
 * relative to when the PREVIOUS one actually happened, and Android's own docs are explicit
 * that periodic work isn't guaranteed to land at the same wall-clock time twice: Doze mode
 * and battery optimization can defer any given run, and that drift compounds day over day.
 * Confirmed via a real report: a run landed at 19:23 UTC instead of the intended 21:00 UTC
 * anchor -- a ~97 minute drift, entirely consistent with this known limitation, not a bug
 * in the anchor calculation itself.
 *
 * Fix: a self-rescheduling chain of OneTimeWorkRequests. MarketDataWorker calls
 * scheduleNext() itself once it reaches a final outcome (success, or genuinely giving up
 * for the day -- see its own comments), and that call recomputes millisUntilNextUtc(21, 0)
 * fresh from the actual current time rather than trusting an interval to stay anchored.
 * Re-anchors to the real target every single day regardless of how late any single run
 * was deferred.
 */
object MarketDataScheduler {
    const val WORK_NAME = "market_data_daily"

    // 21:00 UTC -- chosen to maximise the odds every source has actually published the
    // PREVIOUS trading day's figure by fetch time (see MarketRepository.refreshData(),
    // which now targets AppClock.previousBusinessDay() and verifies each source's own
    // reported date rather than trusting "latest"). US markets close 21:00-22:00 UTC
    // depending on DST; FRED's same-day update for US-sourced series (DGS2, VIXCLS,
    // DCOILWTICO) can lag past market close, so this anchor is intentionally later than
    // the old same-day 16:00 UTC one -- that time was tuned for "today's figure has
    // probably been published," which is a different (and looser) bar than "yesterday's
    // figure is now fully settled everywhere," which is what this now needs.
    // v87: 16:00 UTC, after the ECB reference rate (published ~14:00-16:00 CET) -- the forecast is now made
    // from TODAY's rate, with the other inputs one business day old (see MarketRepository.captureDate).
    const val REFRESH_HOUR_UTC = 16
    const val REFRESH_MINUTE_UTC = 0

    fun millisUntilNextUtc(hour: Int, minute: Int): Long {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return Duration.between(now, target).toMillis()
    }

    /** Called once from GbpUsdPredictorApp.onCreate(). KEEP (not REPLACE) here -- if a
     * chain is already scheduled (the normal case on every process restart), leave it
     * alone; only MarketDataWorker's own scheduleNext() call should ever replace it, once
     * a run actually reaches a final outcome. Enqueueing fresh on every cold start would
     * keep resetting the anchor to "next 21:00 UTC from whenever the app happened to be
     * reopened" instead of holding a stable daily cadence. */
    fun start(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            buildRequest(millisUntilNextUtc(REFRESH_HOUR_UTC, REFRESH_MINUTE_UTC))
        )
    }

    /** Called by MarketDataWorker itself once a run reaches a final outcome. REPLACE here
     * is correct and intentional -- this is the one call that's actually allowed to
     * override whatever's currently scheduled, since it's the worker advancing its own
     * chain forward to tomorrow. */
    fun scheduleNext(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            buildRequest(millisUntilNextUtc(REFRESH_HOUR_UTC, REFRESH_MINUTE_UTC))
        )
    }

    private fun buildRequest(delayMs: Long) =
        OneTimeWorkRequestBuilder<MarketDataWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
}
