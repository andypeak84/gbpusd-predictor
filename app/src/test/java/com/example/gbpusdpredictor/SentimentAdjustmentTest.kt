package com.example.gbpusdpredictor

import com.example.gbpusdpredictor.sentiment.SentimentAdjustment
import org.junit.Assert.assertEquals
import org.junit.Test

class SentimentAdjustmentTest {
    private val eps = 1e-12

    @Test fun noHorizonSdMeansNoShift() {
        assertEquals(0.0, SentimentAdjustment.tilt(0.8, 10, null), eps)
    }

    @Test fun fullStrengthReadingShiftsByWeightTimesHorizonSd() {
        // weight 0.75 x score 1 x full evidence x sd 0.004
        assertEquals(0.003, SentimentAdjustment.tilt(1.0, 3, 0.004, weight = 0.75, fullStrengthStories = 3), eps)
    }

    @Test fun fewerStoriesScaleTheEffectDownLinearly() {
        val full = SentimentAdjustment.tilt(1.0, 3, 0.004, 0.75, 3)
        assertEquals(full / 3.0, SentimentAdjustment.tilt(1.0, 1, 0.004, 0.75, 3), eps)
    }

    @Test fun moreStoriesThanNeededDoNotExceedFullStrength() {
        val full = SentimentAdjustment.tilt(1.0, 3, 0.004, 0.75, 3)
        assertEquals(full, SentimentAdjustment.tilt(1.0, 50, 0.004, 0.75, 3), eps)
    }

    @Test fun scoreIsClampedToPlusMinusOne() {
        assertEquals(SentimentAdjustment.tilt(1.0, 3, 0.004), SentimentAdjustment.tilt(5.0, 3, 0.004), eps)
        assertEquals(SentimentAdjustment.tilt(-1.0, 3, 0.004), SentimentAdjustment.tilt(-9.0, 3, 0.004), eps)
    }

    @Test fun bearishNewsShiftsDownAndBullishUp() {
        assert(SentimentAdjustment.tilt(-0.5, 3, 0.004) < 0.0)
        assert(SentimentAdjustment.tilt(0.5, 3, 0.004) > 0.0)
    }

    @Test fun zeroWeightMeansNewsHasNoEffect() {
        assertEquals(0.0, SentimentAdjustment.tilt(1.0, 3, 0.004, weight = 0.0), eps)
    }

    @Test fun zeroFullStrengthStoriesDoesNotDivideByZero() {
        assertEquals(0.003, SentimentAdjustment.tilt(1.0, 1, 0.004, 0.75, 0), eps)
    }
}
