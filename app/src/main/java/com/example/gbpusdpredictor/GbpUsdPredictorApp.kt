package com.example.gbpusdpredictor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.gbpusdpredictor.work.MarketDataScheduler
import com.example.gbpusdpredictor.work.MarketDataWorker

class GbpUsdPredictorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createDailyForecastChannel()
        MarketDataScheduler.start(this)
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
}
