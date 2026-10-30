package com.example.gbpusdpredictor.work

import android.content.Context
import com.example.gbpusdpredictor.work.PriceAlertPrefs.alphaVantageKey
import com.example.gbpusdpredictor.work.PriceAlertPrefs.priceAlertsEnabled
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * One shared live GBP/USD reading for the whole app. The main screen, the Accuracy screen, the price-alert worker and the key check
 * all read and write THIS, so a quote paid for once is reused everywhere. That matters because Alpha Vantage's free tier is 25
 * requests a day and the hourly alert worker alone can use 24: every fetch goes through [fetchAlphaVantage], which counts it.
 *
 * The only live source is the user's own Alpha Vantage key, used while today's shared counter is under [AV_DAILY_CAP]. With no key
 * (or the budget spent) there is no live reading and the screens show the daily ECB rate, labelled as such. A keyless live source was
 * tried and dropped: Kraken's terms forbid making its prices available to third parties and third-party apps without written consent.
 * The forecast and its scoring stay tied to the DAILY reference rate; this is for showing where the pound is now.
 */
object LiveRate {
    enum class Source(val label: String) { ALPHA_VANTAGE("Alpha Vantage") }

    data class Reading(val rate: Double, val at: Instant, val source: Source)

    /** Alpha Vantage allows 25 a day; one is left spare. The hourly alert worker alone can spend 24. */
    const val AV_DAILY_CAP = 24

    private val FRESH_FOR_QUOTE: Duration = Duration.ofMinutes(15)
    /** When the hourly alert worker keeps the cache warm, an hour-old reading is current enough not to spend a call. */
    private val FRESH_WHEN_WORKER_FEEDS: Duration = Duration.ofMinutes(65)
    /** Past this a cached reading is too old to show as live at all. */
    private val TOO_OLD_TO_SHOW: Duration = Duration.ofHours(12)

    private const val PREFS = "live_rate"
    private const val K_RATE = "rate"
    private const val K_AT = "at_ms"
    private const val K_SOURCE = "source"
    private const val K_AV_DAY = "av_day"
    private const val K_AV_COUNT = "av_count"

    // ---- pure logic (unit-tested) ---------------------------------------------------------------------------------------

    fun isFresh(reading: Reading, now: Instant, maxAge: Duration): Boolean =
        !reading.at.isAfter(now) && Duration.between(reading.at, now) <= maxAge

    /** The sources to try, best first: Alpha Vantage only with a key and budget left; otherwise nothing (the screens show the daily rate). */
    fun sourceOrder(hasKey: Boolean, avCallsToday: Int): List<Source> =
        if (hasKey && avCallsToday < AV_DAILY_CAP) listOf(Source.ALPHA_VANTAGE) else emptyList()

    fun dayKey(now: Instant): String = now.atZone(ZoneOffset.UTC).toLocalDate().toString()

    /** "3 min ago" / "2 h ago", for the line under the live rate. */
    fun ageLabel(reading: Reading, now: Instant): String {
        val mins = Duration.between(reading.at, now).toMinutes().coerceAtLeast(0)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            else -> "${mins / 60} h ago"
        }
    }

    // ---- storage ---------------------------------------------------------------------------------------------------------

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cached(context: Context): Reading? {
        val p = prefs(context)
        if (!p.contains(K_RATE)) return null
        val source = runCatching { Source.valueOf(p.getString(K_SOURCE, "") ?: "") }.getOrNull() ?: return null
        return Reading(Double.fromBits(p.getLong(K_RATE, 0L)), Instant.ofEpochMilli(p.getLong(K_AT, 0L)), source)
    }

    fun record(context: Context, rate: Double, source: Source, at: Instant = Instant.now()): Reading {
        prefs(context).edit().putLong(K_RATE, rate.toBits()).putLong(K_AT, at.toEpochMilli()).putString(K_SOURCE, source.name).apply()
        return Reading(rate, at, source)
    }

    fun avCallsToday(context: Context, now: Instant = Instant.now()): Int {
        val p = prefs(context)
        return if (p.getString(K_AV_DAY, "") == dayKey(now)) p.getInt(K_AV_COUNT, 0) else 0
    }

    private fun noteAvCall(context: Context, now: Instant = Instant.now()) {
        val p = prefs(context)
        val today = dayKey(now)
        val count = if (p.getString(K_AV_DAY, "") == today) p.getInt(K_AV_COUNT, 0) else 0
        p.edit().putString(K_AV_DAY, today).putInt(K_AV_COUNT, count + 1).apply()
    }

    // ---- fetching --------------------------------------------------------------------------------------------------------

    /** The ONLY place that calls Alpha Vantage's quote endpoint: counts the call (failures cost a request too) and caches the rate. */
    suspend fun fetchAlphaVantage(context: Context, apiKey: String): Double {
        noteAvCall(context)
        val rate = fetchLiveRate(apiKey)
        record(context, rate, Source.ALPHA_VANTAGE)
        return rate
    }

    /**
     * The best live reading available now: the cache if fresh, otherwise one Alpha Vantage fetch if the user has a key and budget left.
     * Returns a cached reading up to 12 h old if the fetch fails (its age is shown), or null so the caller shows the daily rate
     * labelled as such.
     */
    suspend fun current(context: Context, now: Instant = Instant.now()): Reading? {
        val key = ApiKeyFormat.clean(with(PriceAlertPrefs) { context.alphaVantageKey })
        val workerFeeds = key.isNotEmpty() && with(PriceAlertPrefs) { context.priceAlertsEnabled }
        val cached = cached(context)
        if (cached != null && isFresh(cached, now, if (workerFeeds) FRESH_WHEN_WORKER_FEEDS else FRESH_FOR_QUOTE)) return cached
        for (source in sourceOrder(key.isNotEmpty(), avCallsToday(context, now))) {
            val fresh = runCatching {
                when (source) {
                    Source.ALPHA_VANTAGE -> Reading(fetchAlphaVantage(context, key), Instant.now(), Source.ALPHA_VANTAGE)
                }
            }.getOrNull()
            if (fresh != null) return fresh
        }
        return cached?.takeIf { Duration.between(it.at, now) <= TOO_OLD_TO_SHOW }
    }
}
