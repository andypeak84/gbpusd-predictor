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
import com.example.gbpusdpredictor.MainActivity
import com.example.gbpusdpredictor.R
import com.example.gbpusdpredictor.sentiment.SentimentRepository
import com.example.gbpusdpredictor.sentiment.SentimentSignal
import java.util.Locale

/**
 * The daily "news sentiment is in" step: fetch the file the sentiment repo's Action published
 * (which also refreshes the cached reading the forecast overlay uses, so the toggle works even
 * if the app hasn't been opened), post a notification summarising it, and advance the chain.
 * Does nothing -- and does NOT reschedule, so the chain ends -- if the user has since turned
 * the toggle off. A failed fetch just skips today (no retry storm); tomorrow's run tries again.
 */
class SentimentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "news_sentiment"
        const val NOTIFICATION_CHANNEL_NAME = "News sentiment"
        private const val NOTIFICATION_ID = 1003
    }

    override suspend fun doWork(): Result {
        if (!SentimentRepository.isEnabled(applicationContext)) return Result.success()

        runCatching { SentimentRepository.refresh(applicationContext) }
            .onSuccess { runCatching { postNotification(it) }.onFailure { e -> Log.w("SentimentWorker", "notify failed", e) } }
            .onFailure { Log.w("SentimentWorker", "fetch failed -- skipping today", it) }

        SentimentScheduler.scheduleNext(applicationContext)
        return Result.success()
    }

    @SuppressLint("MissingPermission") // areNotificationsEnabled() below is the guard, same as the other workers.
    private fun postNotification(signal: SentimentSignal) {
        val context = applicationContext
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val w = signal.w24h
        val age = signal.ageHours()
        val staleNote = if (age > 12.0) " (reading is ${age.toInt()}h old)" else ""
        val stories = signal.headlines.take(3).joinToString("") {
            val arrow = if (it.direction > 0) "▲" else if (it.direction < 0) "▼" else "•"
            "\n$arrow ${it.displayText.take(90)}"
        }
        val body = "${w.label.replaceFirstChar { c -> c.uppercase() }} (${"%+.2f".format(Locale.UK, w.score)}) " +
            "from ${w.articles} ${if (w.articles == 1) "story" else "stories"}$staleNote.$stories"

        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("GBP/USD news sentiment")
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
