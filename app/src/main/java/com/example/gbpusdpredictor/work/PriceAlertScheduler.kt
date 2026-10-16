package com.example.gbpusdpredictor.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Hourly self-rescheduling chain for the price-alert feature -- same chain-of-
 * OneTimeWorkRequests pattern as MarketDataScheduler, for the same reason (a
 * PeriodicWorkRequest's own execution can drift under Doze, and that drift compounds;
 * a chain that recomputes "next hour from now" every run doesn't compound it). Unlike
 * MarketDataScheduler this isn't anchored to a specific wall-clock time -- "hourly" is
 * satisfied by any ~60-minute cadence, so a flat setInitialDelay is sufficient and
 * there's no REFRESH_HOUR_UTC-style anchor to recompute.
 *
 * Distinct WORK_NAME/unique work from MarketDataScheduler's "market_data_daily" --
 * two entirely independent chains, each advancing on its own worker's own completion.
 */
object PriceAlertScheduler {
    const val WORK_NAME = "price_alert_hourly"
    private const val INTERVAL_MINUTES = 60L

    /** Called whenever the user enables alerts in settings (or the chain needs a fresh
     * start) -- REPLACE here is deliberate (unlike MarketDataScheduler.start's KEEP):
     * toggling alerts on should always restart the hourly cadence from "one hour from
     * now," not silently resume whatever was left over from before. */
    fun start(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            buildRequest()
        )
    }

    /** Called by PriceAlertWorker itself once a run completes, to advance the chain --
     * same REPLACE reasoning as MarketDataScheduler.scheduleNext. */
    fun scheduleNext(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            buildRequest()
        )
    }

    /** Called when the user disables alerts in settings -- stops the chain outright
     * rather than relying on PriceAlertWorker's own early-exit (that check only runs
     * once an hour; this takes effect immediately). */
    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private fun buildRequest() =
        OneTimeWorkRequestBuilder<PriceAlertWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
}
