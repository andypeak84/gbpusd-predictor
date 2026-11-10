package com.example.gbpusdpredictor

import com.example.gbpusdpredictor.utils.SupportLevels
import com.example.gbpusdpredictor.work.SupportLevelStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SupportLevelsTest {
    // 130 closes wandering between 1.3300 and 1.3560, ending at 1.3450
    private fun closes(): List<Double> = List(130) { i -> if (i == 40) 1.3300 else if (i == 90) 1.3560 else 1.3400 + (i % 10) * 0.0004 } + 1.3450

    @Test fun showsTheNearestSupportAndResistanceWithTheirDistance() {
        val text = SupportLevels.describe(closes(), 1.3450)
        val lines = text.lines()
        // nearest below: the 20- and 60-day lows are the same price, so they are shown as one level; then the 120-day low
        assertTrue(lines[0], lines[0].startsWith("Support 1.3400 (20-day low and 60-day low, -0.4%)"))
        assertTrue(lines[0], lines[0].contains("1.3300 (120-day low"))
        // nearest above: the round 1.3500, then the 120-day high
        assertTrue(lines[1], lines[1].startsWith("Resistance 1.3500 (round number, +0.4%)"))
        assertTrue(lines[1], lines[1].contains("1.3560 (60-day high and 120-day high"))
    }

    @Test fun tooLittleHistoryShowsNothing() {
        assertEquals("", SupportLevels.describe(List(10) { 1.34 }, 1.34))
    }

    @Test fun nearTheTwentyDayLowSaysSoWithoutPromisingAnything() {
        val flat = List(60) { 1.3600 } + List(20) { 1.3500 + (it % 5) * 0.0010 }  // 20-day low is 1.3500
        val text = SupportLevels.describe(flat, 1.3505)
        assertTrue(text, text.contains("Near its 20-day low"))
        assertTrue(text, text.contains("not a forecast"))
    }

    @Test fun belowTheTwentyDayLowIsNotCalledABounce() {
        val flat = List(60) { 1.3600 } + List(20) { 1.3500 + (it % 5) * 0.0010 }
        val text = SupportLevels.describe(flat, 1.3400)
        assertTrue(text, text.contains("Below its 20-day low"))
        assertFalse(text, text.contains("rose over the next 5 days"))
    }

    @Test fun midRangeShowsNoLowNote() {
        val text = SupportLevels.describe(closes(), 1.3450)
        assertFalse(text, text.contains("20-day low (") )
        assertFalse(text, text.contains("Near its"))
    }

    @Test fun cacheRoundTripsInDateOrder() {
        val series = mapOf(LocalDate.of(2026, 11, 5) to 1.3459, LocalDate.of(2026, 11, 4) to 1.3500, LocalDate.of(2026, 11, 6) to 1.3462)
        assertEquals(listOf(1.3500, 1.3459, 1.3462), SupportLevelStore.decode(SupportLevelStore.encode(series)))
    }

    @Test fun garbledCacheEntriesAreSkipped() {
        assertEquals(listOf(1.34), SupportLevelStore.decode("2026-11-04:1.34,nonsense,2026-11-05:abc"))
    }
}
