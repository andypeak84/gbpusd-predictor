package com.example.gbpusdpredictor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.gbpusdpredictor.work.MarketDataScheduler
import com.example.gbpusdpredictor.work.MarketDataWorker
import com.example.gbpusdpredictor.work.PriceAlertPrefs
import com.example.gbpusdpredictor.work.PriceAlertScheduler
import com.example.gbpusdpredictor.work.PriceAlertWorker
import com.example.gbpusdpredictor.sentiment.SentimentRepository
import com.example.gbpusdpredictor.work.SentimentScheduler
import com.example.gbpusdpredictor.work.SentimentWorker

class GbpUsdPredictorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createDailyForecastChannel()
        createPriceAlertChannel()
        createSentimentChannel()
        MarketDataScheduler.start(this)
        // Daily news-sentiment fetch, only while the user has the toggle on (see SentimentScheduler).
        if (SentimentRepository.isEnabled(this)) SentimentScheduler.start(this)
        // Only (re)start the hourly chain if the user has actually enabled alerts --
        // mirrors PriceAlertScheduler.stop() being called on disable, so a cold start
        // doesn't silently resurrect a chain the user turned off.
        if (with(PriceAlertPrefs) { priceAlertsEnabled }) {
            PriceAlertScheduler.start(this)
        }
    }

    /** minSdk is 26, so a NotificationChannel always exists to attach the daily-forecast
     * notification to (see MarketDataWorker.postForecastNotification). Creating a channel
     * that already exists is a documented no-op, so this is safe to run on every cold
     * start. IMPORTANCE_DEFAULT: a once-a-day heads-up, not a silent status entry, but not
     * urgent enough to interrupt with sound+peek every time either. */
    private fun createDailyForecastChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            MarketDataWorker.NOTIFICATION_CHANNEL_ID,
            MarketDataWorker.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "The daily GBP/USD forecast, posted after the 21:00 UTC refresh."
        }
        manager.createNotificationChannel(channel)
    }

    /** Once-a-day summary, so the same IMPORTANCE_DEFAULT as the daily forecast. */
    private fun createSentimentChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            SentimentWorker.NOTIFICATION_CHANNEL_ID,
            SentimentWorker.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "The daily GBP/USD news-sentiment reading, posted after the morning update."
        }
        manager.createNotificationChannel(channel)
    }

    /** IMPORTANCE_HIGH (unlike the daily forecast's DEFAULT) -- a price alert is, by
     * definition, something the user asked to be interrupted for, not a once-a-day
     * summary to glance at later. */
    private fun createPriceAlertChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            PriceAlertWorker.NOTIFICATION_CHANNEL_ID,
            PriceAlertWorker.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Live GBP/USD threshold and hourly-move alerts you've configured."
        }
        manager.createNotificationChannel(channel)
    }
}
