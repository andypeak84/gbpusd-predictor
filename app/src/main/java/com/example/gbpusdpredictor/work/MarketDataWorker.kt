package com.example.gbpusdpredictor.work

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.gbpusdpredictor.BuildConfig
import com.example.gbpusdpredictor.MainActivity
import com.example.gbpusdpredictor.R
import com.example.gbpusdpredictor.data.AppDatabase
import com.example.gbpusdpredictor.data.MarketData
import com.example.gbpusdpredictor.ml.TreeModelPredictor
import com.example.gbpusdpredictor.network.FrankfurterApi
import com.example.gbpusdpredictor.repository.MarketRepository
import com.example.gbpusdpredictor.repository.RefreshResult
import com.example.gbpusdpredictor.widget.PredictorWidgetProvider
import java.time.LocalDate
import java.util.Locale

/**
 * The ONLY thing that ever writes to market_data now -- MarketDataScheduler's
 * self-rescheduling chain calls this exactly once a day (targeting 21:00 UTC), with no
 * immediate on-open work or manual "Refresh Now" button to race it.
 *
 * Owns advancing the chain: on a FINAL outcome (success, or genuinely giving up on today
 * after MAX_ATTEMPTS_BEFORE_GIVING_UP), it calls MarketDataScheduler.scheduleNext() itself
 * to queue tomorrow's run. On a transient failure under that cap, it returns Result.retry()
 * and lets WorkManager's own backoff retry THIS SAME request -- scheduleNext() is
 * deliberately NOT called in that case, since doing so would REPLACE (see
 * MarketDataScheduler.scheduleNext's own comment) the very retry that's about to happen.
 */
class MarketDataWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        // Bounds how long a genuine outage gets retried before this chain gives up on
        // today's target and lets tomorrow's normal 21:00 UTC run take over. WorkManager's
        // LINEAR backoff (30s * attempt, capped at its own ~5hr max) means this many
        // attempts already spans a large chunk of a day for a persistent failure --
        // deliberately not indefinite, so a multi-day outage doesn't wedge the chain.
        private const val MAX_ATTEMPTS_BEFORE_GIVING_UP = 8

        // A run that finds the target date already captured (SKIPPED_ALREADY_CAPTURED --
        // the normal, correct outcome most days, e.g. every weekend run) leaves ZERO trace
        // anywhere else -- no new market_data row, no bootstrap_fetch_log entries (the
        // dedup check exits before any fetch happens). Confirmed via a real report: without
        // this, "the job ran today and correctly found nothing new to do" was
        // indistinguishable from "the job never ran at all," which is exactly the kind of
        // question this heartbeat exists to answer directly instead of requiring someone to
        // reconstruct the business-day math by hand. Written unconditionally, before the
        // outcome is even known, so a run that crashes mid-way still leaves a trace that an
        // attempt happened.
        const val PREFS_NAME = "market_data_worker_heartbeat"
        const val KEY_LAST_ATTEMPT_UTC = "last_attempt_utc"
        const val KEY_LAST_OUTCOME = "last_outcome"
        const val KEY_LAST_ATTEMPT_NUMBER = "last_attempt_number"

        // Daily "here's today's call" notification, posted only when a run actually
        // captured a NEW row (SUCCESS). The channel itself is created once in
        // GbpUsdPredictorApp.onCreate(); creating it again here would be harmless but the
        // app object always runs first. A fixed notification id means each day's post
        // replaces yesterday's rather than stacking.
        const val NOTIFICATION_CHANNEL_ID = "daily_forecast"
        const val NOTIFICATION_CHANNEL_NAME = "Daily forecast"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_ATTEMPT_UTC, java.time.Instant.now().toString())
            .putInt(KEY_LAST_ATTEMPT_NUMBER, runAttemptCount + 1)
            .apply()

        val treeModel = TreeModelPredictor(applicationContext)
        val repository = MarketRepository(
            database = AppDatabase.getInstance(applicationContext),
            frankfurterService = FrankfurterApi.service,
            treeModel = treeModel
        )

        val result = repository.refreshData()
        Log.i("MarketDataWorker", "doWork: refreshData() -> $result (attempt ${runAttemptCount + 1})")
        prefs.edit().putString(KEY_LAST_OUTCOME, result.name).apply()

        return when (result) {
            RefreshResult.SUCCESS -> {
                notifyWidgets()
                runCatching { postForecastNotification(treeModel) }
                    .onFailure { Log.w("MarketDataWorker", "postForecastNotification failed (non-fatal)", it) }
                MarketDataScheduler.scheduleNext(applicationContext)
                Result.success()
            }
            // Not a failure -- the target trading date was already captured (an earlier
            // run today, or a weekend run landing on the same Friday an earlier run
            // already got -- see RefreshResult's own doc comment). Nothing changed, so
            // widgets don't need updating, but the chain still needs to advance.
            RefreshResult.SKIPPED_ALREADY_CAPTURED -> {
                MarketDataScheduler.scheduleNext(applicationContext)
                Result.success()
            }
            RefreshResult.FAILED -> {
                if (runAttemptCount + 1 >= MAX_ATTEMPTS_BEFORE_GIVING_UP) {
                    Log.w("MarketDataWorker", "doWork: giving up on today's target after $MAX_ATTEMPTS_BEFORE_GIVING_UP attempts -- advancing to tomorrow's normal run.")
                    MarketDataScheduler.scheduleNext(applicationContext)
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        }
    }

    private fun notifyWidgets() {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val ids = manager.getAppWidgetIds(ComponentName(applicationContext, PredictorWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val intent = Intent(applicationContext, PredictorWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        applicationContext.sendBroadcast(intent)
    }

    /** Posts the once-a-day "today's call" notification after a SUCCESS run. Reads the
     * row that was just written and turns its stored 1-day forecast into the same
     * wait / convert-now / stable wording the widget and home screen use. Silently does
     * nothing if the user hasn't granted POST_NOTIFICATIONS (API 33+) -- Notification
     * ManagerCompat.notify() is a no-op in that case, which is why the caller wraps this
     * in runCatching rather than gating on a permission check here. */
    @SuppressLint("MissingPermission") // areNotificationsEnabled() below is the guard; on
    // API 33+ a denied POST_NOTIFICATIONS just makes notify() silently no-op regardless.
    private suspend fun postForecastNotification(treeModel: TreeModelPredictor) {
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return
        val latest: MarketData = AppDatabase.getInstance(applicationContext).marketDao().getLatest() ?: return

        val body = buildNotificationBody(latest, treeModel)

        val openApp = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("GBP/USD daily update")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotificationBody(latest: MarketData, treeModel: TreeModelPredictor): String {
        val rate = latest.gbpUsd
        val predictedRate = latest.predicted14dRate
        val changePct = latest.predicted14dChangePct
        if (rate == null || predictedRate == null || changePct == null) {
            return "New data captured for ${latest.date}. Open the app for the details."
        }
        val target = LocalDate.parse(latest.date).plusDays(MarketRepository.DEFAULT_HORIZON_DAYS.toLong())
        val now = "%.4f".format(Locale.UK, rate)
        val pred = "%.4f".format(Locale.UK, predictedRate)
        // Cutoff scales with this horizon's own typical move size -- see
        // TreeModelPredictor.adviceCutoffPctFor's own comment. Falls back to the legacy
        // fixed 0.15% on its own if the model file has no horizon_sd block.
        val cutoff = treeModel.adviceCutoffPctFor(MarketRepository.DEFAULT_HORIZON_DAYS)
        return when {
            changePct > cutoff -> "Wait: GBP/USD ($now) may rise to ~$pred by $target."
            changePct < -cutoff -> "Convert now: GBP/USD ($now) may fall to ~$pred by $target."
            else -> "GBP/USD ($now) looks broadly stable through $target."
        }
    }
}
