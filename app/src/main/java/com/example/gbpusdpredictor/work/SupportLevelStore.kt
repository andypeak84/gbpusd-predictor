package com.example.gbpusdpredictor.work

import android.content.Context
import com.example.gbpusdpredictor.network.FrankfurterApi
import com.example.gbpusdpredictor.utils.AppClock
import java.time.LocalDate

/**
 * Six months of daily GBP/USD closes (the ECB rate, from the same Frankfurter source the forecast uses) for the support/resistance lines
 * on the main screen. One request a day at most, kept in SharedPreferences as "date:rate,date:rate"; if the request fails the last copy is
 * used, and with no copy at all the lines are simply not shown. Contains no personal data.
 */
object SupportLevelStore {
    private const val PREFS = "support_levels"
    private const val KEY_CLOSES = "closes"
    private const val KEY_FETCHED_ON = "fetched_on"
    private const val LOOKBACK_DAYS = 200L  // ~140 trading days, enough for the 120-day window

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun encode(series: Map<LocalDate, Double>): String =
        series.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }

    fun decode(text: String): List<Double> = text.split(',').mapNotNull { part ->
        val bits = part.split(':')
        if (bits.size != 2) return@mapNotNull null
        runCatching { LocalDate.parse(bits[0]) to bits[1].toDouble() }.getOrNull()
    }.sortedBy { it.first }.map { it.second }

    /** Cached closes, oldest first; empty if none yet. */
    fun cached(context: Context): List<Double> = decode(prefs(context).getString(KEY_CLOSES, "") ?: "")

    /** Fetches at most once a day (UTC); returns the freshest closes it has. Call off the main thread. */
    suspend fun load(context: Context): List<Double> {
        val today = AppClock.today().toString()
        val p = prefs(context)
        if (p.getString(KEY_FETCHED_ON, null) == today) return cached(context)
        val series = runCatching {
            FrankfurterApi.service.getTimeSeries("${AppClock.today().minusDays(LOOKBACK_DAYS)}..${AppClock.today()}")
                .rates.mapNotNull { (d, r) -> r["USD"]?.let { LocalDate.parse(d) to it } }.toMap()
        }.getOrNull()
        if (series != null && series.size >= 20) {
            p.edit().putString(KEY_CLOSES, encode(series)).putString(KEY_FETCHED_ON, today).apply()
        }
        return cached(context)
    }
}
