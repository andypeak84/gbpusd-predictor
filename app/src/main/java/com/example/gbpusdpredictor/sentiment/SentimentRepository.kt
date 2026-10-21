package com.example.gbpusdpredictor.sentiment

import android.content.Context
import com.example.gbpusdpredictor.network.SentimentApi
import org.json.JSONObject

/**
 * Optional news-sentiment overlay on the forecast. OFF by default and toggled from the
 * main screen; when ON, SentimentAdjustment.tilt() shifts each displayed forecast by a
 * bounded amount in the direction of the published news score.
 *
 * What this is NOT: a trained model input. The tree model has no sentiment feature --
 * there is no multi-year sentiment history to train one on (the pipeline has run for days,
 * not years), and it has never been backtested. The overlay is a labelled, judgement-sized
 * nudge on top of the model's own number, shown separately from it on screen so the two
 * are never confused. The publisher keeps a sentiment-history.json record so that, after
 * enough weeks, the overlay can be tested against what GBP/USD actually did and the
 * weight below either justified or set to zero.
 */
data class SentimentWindow(val score: Double, val label: String, val articles: Int)

/** title is empty for a story whose headline text is not republished (BBC) -- the publisher
 * sends our own one-line `reason` for every story, which the screen shows instead. */
data class SentimentHeadline(
    val title: String,
    val source: String,
    val direction: Int,
    val reason: String
) {
    /** What to show for this story: its headline if published, otherwise our own reason. */
    val displayText: String get() = if (title.isNotBlank()) title else reason
}

data class SentimentSignal(
    val generatedTs: Long,
    val w1h: SentimentWindow,
    val w6h: SentimentWindow,
    val w24h: SentimentWindow,
    val headlines: List<SentimentHeadline>
) {
    fun ageHours(nowMs: Long = System.currentTimeMillis()): Double =
        (nowMs / 1000.0 - generatedTs) / 3600.0
}

/** What the overlay is actually using right now: a real published reading, or a local
 * test override (see SentimentRepository.cycleTestMode). */
data class SentimentReading(
    val score: Double,
    val articles: Int,
    val label: String,
    val ageHours: Double?,
    val isTest: Boolean,
    val headlines: List<SentimentHeadline>
)

object SentimentAdjustment {
    /** The shift for a FULL-strength (|score| = 1), well-evidenced reading, as a fraction
     * of the horizon's typical move size (its horizon_sd). 0.25 makes the maximum shift at
     * 1 day about +-0.08% -- roughly the size of the model's own typical 1-day call, so
     * sentiment can tip a borderline forecast or advice line without ever overpowering the
     * model. A judgement value, NOT a fitted one -- see this file's top comment. */
    const val WEIGHT_OF_HORIZON_SD = 0.25

    /** The backend's own screen warns below 3 articles because the average of one or two
     * items swings to +-1; the shift ramps up linearly to full strength at this many
     * scored articles in the window, so a single headline can't move the forecast much. */
    const val FULL_STRENGTH_ARTICLES = 5

    /** Beyond this, a published reading is ignored rather than applied. The publisher runs
     * once a day (06:00 UTC) and GitHub's scheduler can fire hours late, so a healthy reading
     * can be 24h old plus a delay; 30h covers that, and a reading older than this means a
     * whole day's run was missed, so it is dropped rather than applied. */
    const val MAX_AGE_HOURS = 30.0

    /** Fractional shift to add to a forecast's % change (0.001 = +0.1%). */
    fun tilt(score: Double, articles: Int, horizonSdPct: Double?): Double {
        if (horizonSdPct == null) return 0.0
        val evidence = (articles.toDouble() / FULL_STRENGTH_ARTICLES).coerceIn(0.0, 1.0)
        return WEIGHT_OF_HORIZON_SD * score.coerceIn(-1.0, 1.0) * evidence * horizonSdPct
    }
}

object SentimentRepository {
    private const val PREFS = "news_sentiment"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CACHED_JSON = "cached_json"
    private const val KEY_TEST_MODE = "test_mode"

    // 0 = off, 1 = simulated strongly bullish, 2 = simulated strongly bearish.
    private const val TEST_SCORE = 0.8

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun testMode(context: Context): Int = prefs(context).getInt(KEY_TEST_MODE, 0)

    /** off -> bullish -> bearish -> off. Lets the effect on the forecast be seen on demand
     * even when the live reading is neutral (which, with the offline rule scorer, is most
     * of the time). Local only; never published or stored with any prediction. */
    fun cycleTestMode(context: Context): Int {
        val next = (testMode(context) + 1) % 3
        prefs(context).edit().putInt(KEY_TEST_MODE, next).apply()
        return next
    }

    fun parse(json: String): SentimentSignal {
        val root = JSONObject(json)
        val windows = root.getJSONObject("windows")
        fun window(key: String): SentimentWindow = windows.getJSONObject(key).let {
            SentimentWindow(it.getDouble("score"), it.getString("label"), it.getInt("articles"))
        }
        val headlines = root.optJSONArray("headlines")?.let { arr ->
            (0 until arr.length()).map { i ->
                val h = arr.getJSONObject(i)
                // org.json's optString returns the text "null" for a JSON null, not "".
                fun text(key: String) = if (h.isNull(key)) "" else h.optString(key)
                SentimentHeadline(text("title"), text("source"), h.optInt("direction", 0), text("reason"))
            }
        } ?: emptyList()
        return SentimentSignal(root.getLong("generated_ts"), window("1h"), window("6h"), window("24h"), headlines)
    }

    /** Fetches the latest published signal and caches it. Throws on a network or parse
     * failure -- the caller decides how to show that; the previously cached reading (if
     * any) stays usable until it ages out. */
    suspend fun refresh(context: Context): SentimentSignal {
        val raw = SentimentApi.service.getLatestRaw()
        val signal = parse(raw)
        prefs(context).edit().putString(KEY_CACHED_JSON, raw).apply()
        return signal
    }

    fun cached(context: Context): SentimentSignal? =
        prefs(context).getString(KEY_CACHED_JSON, null)?.let { runCatching { parse(it) }.getOrNull() }

    /** The reading the overlay should apply right now, or null if there is nothing usable
     * (no data yet, or the cached reading is older than MAX_AGE_HOURS). The 24h window is
     * used because the forecasts are for days ahead and the 1h/6h windows are far noisier
     * (often zero or one article). */
    fun reading(context: Context): SentimentReading? {
        when (testMode(context)) {
            1 -> return SentimentReading(TEST_SCORE, SentimentAdjustment.FULL_STRENGTH_ARTICLES, "strongly bullish", null, true, emptyList())
            2 -> return SentimentReading(-TEST_SCORE, SentimentAdjustment.FULL_STRENGTH_ARTICLES, "strongly bearish", null, true, emptyList())
        }
        val signal = cached(context) ?: return null
        val age = signal.ageHours()
        if (age > SentimentAdjustment.MAX_AGE_HOURS) return null
        return SentimentReading(signal.w24h.score, signal.w24h.articles, signal.w24h.label, age, false, signal.headlines)
    }
}
