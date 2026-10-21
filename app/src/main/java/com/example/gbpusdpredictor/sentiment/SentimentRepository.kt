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

/** The published reading the overlay is using right now. */
data class SentimentReading(
    val score: Double,
    val articles: Int,
    val label: String,
    val ageHours: Double?,
    val headlines: List<SentimentHeadline>
)

object SentimentAdjustment {
    /** The shift for a FULL-strength (|score| = 1), well-evidenced reading, as a fraction
     * of the horizon's typical move size (its horizon_sd). 0.75 means a full-strength reading
     * shifts the 1-day forecast by about +-0.25% -- larger than the model's own typical 1-day
     * call (~0.12%), so a strong news day can dominate it. Raised from 0.25 (a maximum of
     * ~0.08%) at the owner's request so the news is visible in the forecast: at today's
     * +0.41 reading it moves the 1-day forecast by about +0.1%. A judgement value, NOT a
     * fitted one and never backtested -- see this file's top comment. */
    const val DEFAULT_WEIGHT_OF_HORIZON_SD = 0.75

    /** The weight slider's range on the main screen (0 = news has no effect). */
    const val MAX_WEIGHT_OF_HORIZON_SD = 1.5

    /** The backend's own screen warns below 3 articles because the average of one or two
     * items swings to +-1; the shift ramps up linearly to full strength at this many
     * stories in the window, so a single headline can't move the forecast much. (Was 5; the
     * publisher now merges same-story duplicates, so 3 stories is already a real spread.) */
    const val DEFAULT_FULL_STRENGTH_STORIES = 3
    const val MAX_FULL_STRENGTH_STORIES = 6

    /** Beyond this, a published reading is ignored rather than applied. The publisher runs
     * once a day (06:00 UTC) and GitHub's scheduler can fire hours late, so a healthy reading
     * can be 24h old plus a delay; 30h covers that, and a reading older than this means a
     * whole day's run was missed, so it is dropped rather than applied. */
    const val MAX_AGE_HOURS = 30.0

    /** Fractional shift to add to a forecast's % change (0.001 = +0.1%). weight and
     * fullStrengthStories are the user's own on-screen settings (see SentimentRepository). */
    fun tilt(
        score: Double,
        articles: Int,
        horizonSdPct: Double?,
        weight: Double = DEFAULT_WEIGHT_OF_HORIZON_SD,
        fullStrengthStories: Int = DEFAULT_FULL_STRENGTH_STORIES
    ): Double {
        if (horizonSdPct == null) return 0.0
        val evidence = (articles.toDouble() / fullStrengthStories.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        return weight * score.coerceIn(-1.0, 1.0) * evidence * horizonSdPct
    }
}

object SentimentRepository {
    private const val PREFS = "news_sentiment"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CACHED_JSON = "cached_json"
    private const val KEY_WEIGHT = "weight"
    private const val KEY_FULL_STRENGTH = "full_strength_stories"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // The two on-screen weighting controls. Stored here, read by the forecast every time it renders.
    fun weight(context: Context): Double =
        prefs(context).getFloat(KEY_WEIGHT, SentimentAdjustment.DEFAULT_WEIGHT_OF_HORIZON_SD.toFloat())
            .toDouble().coerceIn(0.0, SentimentAdjustment.MAX_WEIGHT_OF_HORIZON_SD)

    fun setWeight(context: Context, weight: Double) {
        prefs(context).edit().putFloat(KEY_WEIGHT, weight.toFloat()).apply()
    }

    fun fullStrengthStories(context: Context): Int =
        prefs(context).getInt(KEY_FULL_STRENGTH, SentimentAdjustment.DEFAULT_FULL_STRENGTH_STORIES)
            .coerceIn(1, SentimentAdjustment.MAX_FULL_STRENGTH_STORIES)

    fun setFullStrengthStories(context: Context, stories: Int) {
        prefs(context).edit().putInt(KEY_FULL_STRENGTH, stories).apply()
    }

    fun resetTuning(context: Context) {
        prefs(context).edit().remove(KEY_WEIGHT).remove(KEY_FULL_STRENGTH).apply()
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
        val signal = cached(context) ?: return null
        val age = signal.ageHours()
        if (age > SentimentAdjustment.MAX_AGE_HOURS) return null
        return SentimentReading(signal.w24h.score, signal.w24h.articles, signal.w24h.label, age, signal.headlines)
    }
}
