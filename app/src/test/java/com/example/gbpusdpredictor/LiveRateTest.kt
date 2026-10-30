package com.example.gbpusdpredictor

import com.example.gbpusdpredictor.network.KrakenParser
import com.example.gbpusdpredictor.work.LiveRate
import com.example.gbpusdpredictor.work.LiveRate.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class LiveRateTest {
    private val now = Instant.parse("2026-10-30T07:00:00Z")
    private fun reading(minsAgo: Long, source: Source = Source.KRAKEN) = LiveRate.Reading(1.339, now.minus(Duration.ofMinutes(minsAgo)), source)

    @Test fun aReadingIsFreshUntilItsMaxAge() {
        assertTrue(LiveRate.isFresh(reading(3), now, Duration.ofMinutes(15)))
        assertTrue(LiveRate.isFresh(reading(15), now, Duration.ofMinutes(15)))
        assertFalse(LiveRate.isFresh(reading(16), now, Duration.ofMinutes(15)))
    }

    @Test fun aReadingFromTheFutureIsNotTrusted() {
        assertFalse(LiveRate.isFresh(reading(-5), now, Duration.ofMinutes(15)))
    }

    @Test fun withAKeyAndBudgetLeftAlphaVantageComesFirstThenKraken() {
        assertEquals(listOf(Source.ALPHA_VANTAGE, Source.KRAKEN), LiveRate.sourceOrder(hasKey = true, avCallsToday = 3))
    }

    @Test fun onceTheDailyBudgetIsSpentOnlyKrakenIsUsed() {
        assertEquals(listOf(Source.KRAKEN), LiveRate.sourceOrder(hasKey = true, avCallsToday = LiveRate.AV_DAILY_CAP))
        assertEquals(listOf(Source.ALPHA_VANTAGE, Source.KRAKEN), LiveRate.sourceOrder(true, LiveRate.AV_DAILY_CAP - 1))
    }

    @Test fun withoutAKeyOnlyKrakenIsUsed() {
        assertEquals(listOf(Source.KRAKEN), LiveRate.sourceOrder(hasKey = false, avCallsToday = 0))
    }

    @Test fun withKrakenSwitchedOffAndNoKeyNothingIsFetched() {
        assertTrue(LiveRate.sourceOrder(hasKey = false, avCallsToday = 0, krakenEnabled = false).isEmpty())
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

    @Test fun krakenReplyGivesTheMidOfBidAndAsk() {
        val json = """{"error":[],"result":{"ZGBPZUSD":{"a":["1.33928","422","422.000"],"b":["1.33911","8632","8632.000"],"c":["1.33911","5.0"]}}}"""
        assertEquals((1.33928 + 1.33911) / 2.0, KrakenParser.midRate(json)!!, 1e-9)
    }

    @Test fun krakenErrorsAndGarbageGiveNothing() {
        assertNull(KrakenParser.midRate("""{"error":["EQuery:Unknown asset pair"],"result":{}}"""))
        assertNull(KrakenParser.midRate("not json"))
        assertNull(KrakenParser.midRate("""{"error":[],"result":{}}"""))
    }

    @Test fun implausibleOrCrossedQuotesAreRejected() {
        assertNull(KrakenParser.midRate("""{"error":[],"result":{"X":{"a":["133.9","1","1"],"b":["133.8","1","1"]}}}"""))
        assertNull(KrakenParser.midRate("""{"error":[],"result":{"X":{"a":["1.33","1","1"],"b":["1.34","1","1"]}}}"""))
    }
}
