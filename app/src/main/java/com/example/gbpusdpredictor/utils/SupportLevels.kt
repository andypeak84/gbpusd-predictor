package com.example.gbpusdpredictor.utils

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Support and resistance, shown as information only: it changes no forecast.
 *
 * Levels are the recent lows and highs of the daily ECB rate (20, 60 and 120 trading days) and the round 0.05 numbers (1.3000, 1.3500...),
 * which FX traders watch. The nearest one below the live rate is the support, the nearest above the resistance. Past data (17 years of
 * GBP/USD, see CHANGELOG v98) shows only a faint pattern: after the pound first dipped to a recent low it rose over the next 5 days about
 * 56-59% of the time against 49% on an ordinary day. That is a hint from overlapping events, not a forecast, and the text says so.
 */
object SupportLevels {
    private const val ROUND_STEP = 0.05
    private const val NEAR = 0.0025         // "at" a level: within 0.25%
    private const val SAME_LEVEL = 0.0005   // two levels this close (0.05%) are shown as one

    private data class Candidate(val price: Double, val labels: MutableList<String>)

    /** Closes oldest first, at least 20 of them; the live (or latest) rate. Returns the lines to show, or "" if there is too little history. */
    fun describe(closesOldestFirst: List<Double>, rate: Double): String {
        if (closesOldestFirst.size < 20 || rate <= 0) return ""
        fun window(n: Int) = closesOldestFirst.takeLast(n)
        val lows = listOf(20, 60, 120).filter { closesOldestFirst.size >= it }.map { it to window(it).min() }
        val highs = listOf(20, 60, 120).filter { closesOldestFirst.size >= it }.map { it to window(it).max() }

        val below = ArrayList<Candidate>()
        val above = ArrayList<Candidate>()
        fun add(list: MutableList<Candidate>, price: Double, label: String) {
            val same = list.firstOrNull { abs(it.price / price - 1) <= SAME_LEVEL }
            if (same != null) same.labels += label else list += Candidate(price, mutableListOf(label))
        }
        lows.forEach { (n, p) -> if (p < rate * (1 - SAME_LEVEL)) add(below, p, "$n-day low") }
        highs.forEach { (n, p) -> if (p > rate * (1 + SAME_LEVEL)) add(above, p, "$n-day high") }
        var roundBelow = floor(rate / ROUND_STEP) * ROUND_STEP
        if (roundBelow >= rate * (1 - SAME_LEVEL)) roundBelow -= ROUND_STEP
        var roundAbove = ceil(rate / ROUND_STEP) * ROUND_STEP
        if (roundAbove <= rate * (1 + SAME_LEVEL)) roundAbove += ROUND_STEP
        add(below, roundBelow, "round number")
        add(above, roundAbove, "round number")

        val supports = below.sortedByDescending { it.price }.take(2)
        val resistances = above.sortedBy { it.price }.take(2)

        fun line(title: String, cs: List<Candidate>) = cs.joinToString(" · ", prefix = "$title ") { c ->
            "${"%.4f".format(Locale.UK, c.price)} (${c.labels.joinToString(" and ")}, ${"%+.1f".format(Locale.UK, (c.price / rate - 1) * 100)}%)"
        }

        val low20 = lows.firstOrNull { it.first == 20 }?.second
        val note = when {
            low20 == null -> ""
            rate < low20 * (1 - NEAR) ->
                "Below its 20-day low (${"%.4f".format(Locale.UK, low20)}): there is no reliable pattern after a break."
            rate <= low20 * (1 + NEAR) ->
                "Near its 20-day low. In 17 years of data, after the pound first dipped to a recent low it rose over the next 5 days about " +
                    "56-59% of the time, against 49% on an ordinary day. A faint hint, not a forecast."
            else -> ""
        }
        return buildString {
            append(line("Support", supports))
            append('\n').append(line("Resistance", resistances))
            if (note.isNotEmpty()) append('\n').append(note)
        }
    }
}
