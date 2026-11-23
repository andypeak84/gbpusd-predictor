package com.example.gbpusdpredictor.utils

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A rough chance that a forecast "hits", shown under the forecast. Not a tested probability: it combines two things the app already knows.
 *  - How wide the outcomes usually are: the horizon's typical move (the model's horizon_sd), treated as a normal spread.
 *  - How much of its own forecast the model has actually delivered: its calibration slope, about 0.14 (ml/train_model.py's holdout, 0.138),
 *    i.e. real moves have been about 14% of the size forecast. So the middle of the outcomes is NOT the forecast but a small step towards it,
 *    taken from the live rate when there is one (the market has moved since the reference, and the live rate is the best starting point).
 * On the Accuracy screen the 7-day call has been right 38% of the time (17 of 45, 10 Nov), which fits this picture: close to a coin flip.
 */
object ForecastOdds {
    /** Share of the forecast move that actually shows up in the centre of the outcomes. */
    const val CALIBRATION = 0.14

    data class Odds(
        val higher: Double,     // chance the rate ends above the reference the forecast started from
        val inBand: Double,     // chance it ends inside the forecast +/- the model's average miss
        val reach: Double       // chance it gets to the forecast or beyond (above it for a rise, below it for a fall)
    )

    /** Standard normal CDF (Abramowitz-Stegun 7.1.26, error under 1.5e-7). */
    fun cdf(z: Double): Double {
        val t = 1.0 / (1.0 + 0.2316419 * abs(z))
        val d = 0.3989423 * exp(-z * z / 2.0)
        val p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))))
        return if (z >= 0) 1.0 - p else p
    }

    /**
     * @param forecastChange the forecast as a fraction of the reference (0.0101 = +1.01%)
     * @param referenceRate the daily ECB rate the forecast starts from
     * @param startRate where the pound is now (live rate), or the reference when there is no live rate
     * @param horizonSd the horizon's typical move as a fraction (0.01098 = 1.098%)
     * @param maeFraction the model's average miss at this horizon as a fraction of the rate
     */
    fun compute(forecastChange: Double, referenceRate: Double, startRate: Double, horizonSd: Double, maeFraction: Double): Odds? {
        if (referenceRate <= 0 || startRate <= 0 || horizonSd <= 0) return null
        val centre = startRate * (1.0 + CALIBRATION * forecastChange)
        val spread = horizonSd * referenceRate
        val forecastRate = referenceRate * (1.0 + forecastChange)
        val band = forecastRate * maeFraction
        fun below(x: Double) = cdf((x - centre) / spread)
        return Odds(
            higher = 1.0 - below(referenceRate),
            inBand = below(forecastRate + band) - below(forecastRate - band),
            reach = if (forecastChange >= 0) 1.0 - below(forecastRate) else below(forecastRate)
        )
    }

    private fun pct(x: Double) = "${Math.round(x * 100)}%"

    /** "higher 55% · in band 36% · reach 21%": the short form for a list of horizons. */
    fun describeShort(o: Odds): String = "higher ${pct(o.higher)} · in band ${pct(o.inBand)} · reach ${pct(o.reach)}"

    /** "higher than the reference 55% · inside the forecast ± band 36% · reach 1.3206 or more 21%" */
    fun describe(o: Odds, forecastRate: Double, rising: Boolean): String =
        "higher than the reference ${pct(o.higher)} · inside the forecast ± band ${pct(o.inBand)} · " +
            "reach ${"%.4f".format(java.util.Locale.UK, forecastRate)} or ${if (rising) "more" else "less"} ${pct(o.reach)}"
}
