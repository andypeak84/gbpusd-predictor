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
 * Once-a-day fetch of the published news-sentiment file, plus a notification. Same
 * self-rescheduling OneTimeWorkRequest chain as MarketDataScheduler and PriceAlertScheduler
 * (a periodic request drifts under Doze and the drift compounds; a chain re-anchors to the
 * next 08:00 UTC from the real clock every run).
 *
 * 08:00 UTC is two hours after the sentiment repo's Action is scheduled (06:00 UTC) -- a buffer
 * because GitHub's scheduler is best-effort and can fire hours late. If it was very late the
 * fetch simply reads yesterday's file, and the notification says how old the reading is.
 *
 * Only runs while the "Use news sentiment" toggle is on; turning it off cancels the chain.
 */
object SentimentScheduler {
    const val WORK_NAME = "news_sentiment_daily"
    private const val FETCH_HOUR_UTC = 8

    /** Cold start: KEEP an already-scheduled chain, so reopening the app doesn't keep
     * resetting the anchor (same reasoning as MarketDataScheduler.start). */
    fun start(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)

    /** Toggle switched on: begin a fresh chain. */
    fun startFresh(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE)

    /** Called by SentimentWorker itself to advance the chain to tomorrow. */
    fun scheduleNext(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE)

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<SentimentWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(MarketDataScheduler.millisUntilNextUtc(FETCH_HOUR_UTC, 0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }
}
