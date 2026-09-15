package com.example.gbpusdpredictor.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.gbpusdpredictor.MainActivity
import com.example.gbpusdpredictor.R
import com.example.gbpusdpredictor.data.AppDatabase
import com.example.gbpusdpredictor.repository.MarketRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

/**
 * Home-screen widget: shows the current GBP/USD rate and, when the tree model has produced
 * a forecast, whether the next few days look better for transferring GBP -> USD now or
 * later. Uses MarketRepository.DEFAULT_HORIZON_DAYS -- the in-app screen lets you pick
 * any horizon from 1-7 days; the widget just shows the default one.
 * Tapping it opens MainActivity, which shows the full feature breakdown behind the number.
 */
class PredictorWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // One row per trading day now (see MarketData.kt's v5->v6 note) --
                // getLatest() is simply the most recent trading date on record; no more
                // "best of today" distinction needed since there's only ever one row.
                val latest = AppDatabase.getInstance(context).marketDao().getLatest()
                val views = RemoteViews(context.packageName, R.layout.widget_predictor)

                if (latest == null) {
                    views.setTextViewText(R.id.widget_rate, "--")
                    views.setTextViewText(R.id.widget_signal, "No data yet — open the app")
                } else if (latest.gbpUsd == null) {
                    // Real possibility now that a miss is null instead of a fallback --
                    // see MarketData.kt's v5->v6 note. No fabricated number to show.
                    views.setTextViewText(R.id.widget_rate, "--")
                    views.setTextViewText(R.id.widget_signal, "No reading for ${latest.date} -- open the app")
                } else {
                    views.setTextViewText(R.id.widget_rate, String.format(Locale.UK, "%.4f", latest.gbpUsd))

                    val predictedRate = latest.predicted14dRate
                    val changePct = latest.predicted14dChangePct
                    if (predictedRate == null || changePct == null) {
                        views.setTextViewText(R.id.widget_signal, "No forecast yet")
                    } else {
                        val targetDate = LocalDate.parse(latest.date).plusDays(MarketRepository.DEFAULT_HORIZON_DAYS.toLong())
                        val signal = when {
                            changePct > 0.0015 ->
                                "Wait: rate may rise to ~%.4f by %s".format(Locale.UK, predictedRate, targetDate)
                            changePct < -0.0015 ->
                                "Transfer now: rate may fall to ~%.4f by %s".format(Locale.UK, predictedRate, targetDate)
                            else ->
                                "Stable over the next ${MarketRepository.DEFAULT_HORIZON_DAYS} days"
                        }
                        views.setTextViewText(R.id.widget_signal, signal)
                    }
                }

                val openAppIntent = Intent(context, MainActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    widgetId,
                    openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

                appWidgetManager.updateAppWidget(widgetId, views)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
