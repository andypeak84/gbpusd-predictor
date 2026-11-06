package com.example.gbpusdpredictor.utils

import java.util.Locale
import kotlin.math.abs

/**
 * "How much of the forecast move has the live rate already made?" The forecast is a move from the daily ECB reference to the next
 * ECB rate; by the time anyone looks, the live rate has usually moved from that reference already. Comparing the two says whether
 * the forecast is still ahead of the market, already reached, or going the other way. Information only: it changes no forecast.
 */
object SinceFix {
    /** Below this, a forecast move is treated as flat (0.03%): a share of nothing means nothing. */
    private const val FLAT = 0.0003

    /** Live move below this (0.01%) is level with the reference. */
    private const val LEVEL = 0.0001

    /** @param liveMove live rate / reference - 1 (a fraction), @param forecastMove the forecast change from the reference (a fraction). */
    fun describe(liveMove: Double, forecastMove: Double): String {
        fun pct(x: Double) = "%+.2f%%".format(Locale.UK, x * 100)
        return when {
            abs(liveMove) < LEVEL -> "Live is level with the reference, so none of the forecast move has happened yet."
            abs(forecastMove) < FLAT -> "The forecast is close to flat; live has moved ${pct(liveMove)} since the reference."
            liveMove * forecastMove < 0 ->
                "Live has moved the other way since the reference: ${pct(liveMove)}, against a forecast of ${pct(forecastMove)}."
            abs(liveMove) >= abs(forecastMove) ->
                "Live has already reached the forecast: ${pct(liveMove)} since the reference, the forecast move is ${pct(forecastMove)}."
            else ->
                "About ${"%.0f".format(Locale.UK, 100 * liveMove / forecastMove)}% of the forecast move has already happened: " +
                    "live ${pct(liveMove)} of a forecast ${pct(forecastMove)}."
        }
    }
}
