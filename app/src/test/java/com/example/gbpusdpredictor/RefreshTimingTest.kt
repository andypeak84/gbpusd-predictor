package com.example.gbpusdpredictor

import com.example.gbpusdpredictor.repository.MarketRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

class RefreshTimingTest {
    private val monday = LocalDate.of(2026, 11, 2)

    private fun at(date: LocalDate, hour: Int) = ZonedDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, 0, 0, 0, ZoneOffset.UTC)

    @Test fun earlyMorningRunCannotHaveTodaysEcbRate() {
        assertFalse(MarketRepository.ecbRateCanExist(monday, at(monday, 6)))
        assertFalse(MarketRepository.ecbRateCanExist(monday, at(monday, 11)))
    }

    @Test fun afternoonRunMayHaveTodaysEcbRate() {
        assertTrue(MarketRepository.ecbRateCanExist(monday, at(monday, 12)))
        assertTrue(MarketRepository.ecbRateCanExist(monday, at(monday, 21)))
    }

    @Test fun pastDatesAreAlwaysFetchableEvenInTheMorning() {
        assertTrue(MarketRepository.ecbRateCanExist(monday.minusDays(3), at(monday, 6)))
    }

    private val gilt = mapOf(
        LocalDate.of(2026, 10, 30) to 4.68, // Friday
        LocalDate.of(2026, 11, 2) to 4.62   // Monday
    )

    @Test fun exactDateWins() {
        assertEquals(4.62, MarketRepository.giltYieldFor(gilt, LocalDate.of(2026, 11, 2))!!, 1e-9)
    }

    @Test fun previousBusinessDayStandsInWhenTodaysIsMissing() {
        // Tuesday's yield is not out yet; Monday's is one business day older.
        assertEquals(4.62, MarketRepository.giltYieldFor(gilt, LocalDate.of(2026, 11, 3))!!, 1e-9)
    }

    @Test fun mondayFallsBackToFridayAcrossTheWeekend() {
        val onlyFriday = mapOf(LocalDate.of(2026, 10, 30) to 4.68)
        assertEquals(4.68, MarketRepository.giltYieldFor(onlyFriday, LocalDate.of(2026, 11, 2))!!, 1e-9)
    }

    @Test fun anythingOlderThanOneBusinessDayIsNotUsed() {
        assertNull(MarketRepository.giltYieldFor(gilt, LocalDate.of(2026, 11, 4)))
    }

    @Test fun aLaterDateIsNeverUsed() {
        assertNull(MarketRepository.giltYieldFor(mapOf(LocalDate.of(2026, 11, 4) to 4.7), LocalDate.of(2026, 11, 3)))
    }
}
