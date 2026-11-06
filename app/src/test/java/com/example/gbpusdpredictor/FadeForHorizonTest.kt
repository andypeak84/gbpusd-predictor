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

    @Test fun tiltAppliesTheFadeForTheHorizonItIsGiven() {
        val oneDay = SentimentAdjustment.tilt(0.84, 16, 0.4412, 0.43, 6, horizonDays = 1)
        assertEquals(0.43 * 0.84 * 0.4412, oneDay, 1e-12)
        // 3 days: the typical move is bigger (0.7317) but half the shift is kept
        assertEquals(0.43 * 0.84 * 0.7317 * 0.5, SentimentAdjustment.tilt(0.84, 16, 0.7317, 0.43, 6, horizonDays = 3), 1e-12)
        // 5 days and beyond: no news shift at all, however strong the reading
        assertEquals(0.0, SentimentAdjustment.tilt(1.0, 99, 1.0978, 1.5, 1, horizonDays = 7), 1e-12)
    }

    @Test fun theOneDayShiftIsUnchangedWhenNoHorizonIsGiven() {
        assertEquals(SentimentAdjustment.tilt(0.5, 4, 0.4412, 0.75, 3, horizonDays = 1), SentimentAdjustment.tilt(0.5, 4, 0.4412, 0.75, 3), 1e-15)
    }
}
