package com.example.gbpusdpredictor.work

import android.annotation.SuppressLint
import android.app.PendingIntent
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
import com.example.gbpusdpredictor.network.AlphaVantageApi
import java.time.Instant
import java.util.Locale

/**
 * Hourly live-rate check for the price-alert feature -- entirely separate from
 * MarketDataWorker's once-a-day, previousBusinessDay()-anchored refresh (see that
 * class's own comment). This fetches Alpha Vantage's near-real-time GBP/USD quote,
 * checks it against whatever the user has configured in PriceAlertPrefs, and fires a
 * notification on a breach. It does NOT write to market_data or affect any prediction
 * -- purely a live watch-and-alert, independent of the tree model entirely.
 *
 * Two trigger kinds, independently optional (both null/unset == that trigger is off):
 *  - Absolute level: upperThreshold / lowerThreshold, e.g. "tell me if it crosses 1.35
 *    or falls below 1.30" -- checked against the single latest rate.
 *  - Hourly % move: hourlyMovePct, e.g. "tell me if it moves >0.5% within an hour" --
 *    checked against lastHourlyRate, which this worker itself updates after every
 *    fetch (whether or not that fetch triggered an alert), so the comparison is always
 *    against the immediately-preceding hourly reading.
 *
 * Self-rescheduling chain, same pattern/reasoning as MarketDataScheduler (periodic
 * WorkManager drifts under Doze; a OneTimeWorkRequest chain re-anchors to "next full
 * hour" fresh every run instead of compounding drift) -- just anchored hourly instead
 * of to a daily UTC clock time.
 */
class PriceAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "price_alert"
        const val NOTIFICATION_CHANNEL_NAME = "Price alerts"
        private const val NOTIFICATION_ID = 1002
    }

    override suspend fun doWork(): Result {
        with(PriceAlertPrefs) {
            if (!applicationContext.priceAlertsEnabled) {
                // User turned alerts off since the chain was last scheduled -- stop
                // rescheduling entirely rather than burning an API call every hour for
                // nothing. PriceAlertScheduler.start() (called when the user re-enables
                // in settings) restarts the chain.
                return Result.success()
            }
        }

        with(PriceAlertPrefs) { applicationContext.lastFetchAttemptUtc = Instant.now().toString() }

        val fetchResult = runCatching { fetchLiveRate() }
        val rate = fetchResult.getOrNull()

        with(PriceAlertPrefs) {
            applicationContext.lastFetchOutcome = fetchResult.exceptionOrNull()?.let { "ERROR: ${it.message}" } ?: "OK"
        }

        if (rate != null) {
            checkAndMaybeAlert(applicationContext, rate)
        } else {
            Log.w("PriceAlertWorker", "doWork: live rate fetch failed (${fetchResult.exceptionOrNull()?.message}) -- skipping this hour, chain continues normally.")
        }

        PriceAlertScheduler.scheduleNext(applicationContext)
        return Result.success()
    }

    private fun checkAndMaybeAlert(context: Context, rate: Double) {
        with(PriceAlertPrefs) {
            val upper = context.upperThreshold
            val lower = context.lowerThreshold
            val movePct = context.hourlyMovePct
            val previousRate = context.lastHourlyRate

            val breaches = mutableListOf<String>()
            if (upper != null && rate >= upper) {
                breaches += "GBP/USD has risen to %.4f, at or above your %.4f alert level.".format(Locale.UK, rate, upper)
            }
            if (lower != null && rate <= lower) {
                breaches += "GBP/USD has fallen to %.4f, at or below your %.4f alert level.".format(Locale.UK, rate, lower)
            }
            if (movePct != null && previousRate != null && previousRate != 0.0) {
                val change = (rate - previousRate) / previousRate
                if (kotlin.math.abs(change) >= movePct) {
                    val direction = if (change > 0) "risen" else "fallen"
                    breaches += "GBP/USD has %s %.2f%% in the last hour, to %.4f.".format(Locale.UK, direction, kotlin.math.abs(change) * 100, rate)
                }
            }

            // Update the hourly baseline unconditionally -- every fetch, alert or not --
            // so the % move check is always "vs one hour ago," never "vs whenever the
            // last alert happened to fire."
            context.lastHourlyRate = rate
            context.lastHourlyRateUtc = Instant.now().toString()

            if (breaches.isNotEmpty()) {
                postAlertNotification(context, breaches.joinToString("\n"))
            }
        }
    }

    @SuppressLint("MissingPermission") // areNotificationsEnabled() below is the guard --
    // same pattern as MarketDataWorker.postForecastNotification.
    private fun postAlertNotification(context: Context, body: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("GBP/USD price alert")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}

/** Shared by the real hourly worker and the settings screen's "Send Test Alert"
 * button, so both go through the exact same parse/error path. Alpha Vantage returns
 * HTTP 200 with an "Error Message"/"Note"/"Information" field instead of a 4xx/5xx for
 * a bad key, a rate-limit hit, or similar -- all of which must be checked explicitly,
 * since none of them throw and a null/blank exchangeRate would otherwise silently
 * become a NumberFormatException deep in toDouble(). */
suspend fun fetchLiveRate(): Double {
    val response = AlphaVantageApi.service.getExchangeRate(apiKey = BuildConfig.ALPHA_VANTAGE_API_KEY)
    response.errorMessage?.let { throw IllegalStateException("Alpha Vantage error: $it") }
    response.note?.let { throw IllegalStateException("Alpha Vantage rate limit: $it") }
    response.information?.let { throw IllegalStateException("Alpha Vantage: $it") }
    val raw = response.rate?.exchangeRate
        ?: throw IllegalStateException("Alpha Vantage returned no exchange rate")
    return raw.toDoubleOrNull()
        ?: throw IllegalStateException("Alpha Vantage returned a non-numeric rate: $raw")
}
