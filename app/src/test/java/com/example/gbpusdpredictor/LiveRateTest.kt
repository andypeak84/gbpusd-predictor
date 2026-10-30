package com.example.gbpusdpredictor

import com.example.gbpusdpredictor.work.LiveRate
import com.example.gbpusdpredictor.work.LiveRate.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class LiveRateTest {
    private val now = Instant.parse("2026-10-30T07:00:00Z")
    private fun reading(minsAgo: Long, source: Source = Source.ALPHA_VANTAGE) = LiveRate.Reading(1.339, now.minus(Duration.ofMinutes(minsAgo)), source)

    @Test fun aReadingIsFreshUntilItsMaxAge() {
        assertTrue(LiveRate.isFresh(reading(3), now, Duration.ofMinutes(15)))
        assertTrue(LiveRate.isFresh(reading(15), now, Duration.ofMinutes(15)))
        assertFalse(LiveRate.isFresh(reading(16), now, Duration.ofMinutes(15)))
    }

    @Test fun aReadingFromTheFutureIsNotTrusted() {
        assertFalse(LiveRate.isFresh(reading(-5), now, Duration.ofMinutes(15)))
    }

    @Test fun withAKeyAndBudgetLeftAlphaVantageIsTheOnlySource() {
        assertEquals(listOf(Source.ALPHA_VANTAGE), LiveRate.sourceOrder(hasKey = true, avCallsToday = 3))
        assertEquals(listOf(Source.ALPHA_VANTAGE), LiveRate.sourceOrder(true, LiveRate.AV_DAILY_CAP - 1))
    }

    @Test fun onceTheDailyBudgetIsSpentThereIsNoLiveSource() {
        assertTrue(LiveRate.sourceOrder(hasKey = true, avCallsToday = LiveRate.AV_DAILY_CAP).isEmpty())
    }

    @Test fun withoutAKeyThereIsNoLiveSource() {
        assertTrue(LiveRate.sourceOrder(hasKey = false, avCallsToday = 0).isEmpty())
    }

    @Test fun theDailyBudgetRollsOverAtUtcMidnight() {
        assertEquals("2026-10-30", LiveRate.dayKey(Instant.parse("2026-10-30T23:59:59Z")))
        assertEquals("2026-10-31", LiveRate.dayKey(Instant.parse("2026-10-31T00:00:00Z")))
    }

    @Test fun ageLabelsAreReadable() {
        assertEquals("just now", LiveRate.ageLabel(reading(0), now))
        assertEquals("3 min ago", LiveRate.ageLabel(reading(3), now))
        assertEquals("2 h ago", LiveRate.ageLabel(reading(130), now))
    }
}
