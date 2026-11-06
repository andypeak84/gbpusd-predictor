package com.example.gbpusdpredictor

import com.example.gbpusdpredictor.sentiment.SentimentAdjustment
import org.junit.Assert.assertEquals
import org.junit.Test

class FadeForHorizonTest {
    @Test fun fullStrengthAtOneDayThenDownAQuarterADay() {
        assertEquals(1.0, SentimentAdjustment.fadeForHorizon(1), 1e-12)
        assertEquals(0.75, SentimentAdjustment.fadeForHorizon(2), 1e-12)
        assertEquals(0.5, SentimentAdjustment.fadeForHorizon(3), 1e-12)
        assertEquals(0.25, SentimentAdjustment.fadeForHorizon(4), 1e-12)
    }

    @Test fun noneFromFiveDaysOn() {
        for (d in 5..14) assertEquals(0.0, SentimentAdjustment.fadeForHorizon(d), 1e-12)
    }

    @Test fun fadedShiftAtSevenDaysIsZeroWhileTheUnfadedOneGrows() {
        val one = SentimentAdjustment.tilt(0.84, 16, 0.4412, 0.43, 6)
        val seven = SentimentAdjustment.tilt(0.84, 16, 1.0978, 0.43, 6)
        assertEquals(true, seven > 2 * one)
        assertEquals(0.0, seven * SentimentAdjustment.fadeForHorizon(7), 1e-12)
    }
}
