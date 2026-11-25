package com.example.gbpusdpredictor.utils

/**
 * The UK-US 2-year spread's change in percentage points, recovered from what the app stores: today's spread and its % change (the
 * model's input, trained that way in ml/train_model.py). A % change is meaningless when the spread sits near zero, so the screen shows
 * this instead: a move from -0.003 to +0.050 is +0.053 points, but -1695% as a % change.
 */
object SpreadMove {
    fun points(spread: Double?, change: Double?): Double? {
        if (spread == null || change == null) return null
        val factor = 1.0 + change
        if (kotlin.math.abs(factor) < 1e-9) return null   // the earlier spread would be infinite: no meaningful answer
        val earlier = spread / factor
        return spread - earlier
    }
}
