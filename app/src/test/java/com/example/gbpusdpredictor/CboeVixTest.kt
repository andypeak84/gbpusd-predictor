package com.example.gbpusdpredictor

import com.example.gbpusdpredictor.repository.MarketRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CboeVixTest {
    // The real file's header and its last rows on 23 Nov 2026
    private val csv = """
        DATE,OPEN,HIGH,LOW,CLOSE
        11/17/2026,23.110000,27.730000,22.360000,25.460000
        11/18/2026,24.220000,26.260000,23.450000,24.560000
        11/19/2026,22.060000,29.130000,21.840000,28.440000
        11/20/2026,28.630000,40.030000,26.940000,35.890000
    """.trimIndent()

    @Test fun readsTheCloseForEachDate() {
        val m = MarketRepository.parseCboeVixCsv(csv)
        assertEquals(4, m.size)
        assertEquals(35.89, m.getValue(LocalDate.of(2026, 11, 20)), 1e-9)
        assertEquals(25.46, m.getValue(LocalDate.of(2026, 11, 17)), 1e-9)
    }

    @Test fun skipsTheHeaderAndBrokenLines() {
        val m = MarketRepository.parseCboeVixCsv("DATE,OPEN,HIGH,LOW,CLOSE\nnonsense\n11/20/2026,1,2,3,abc\n11/19/2026,1,2,3,28.44\n")
        assertEquals(mapOf(LocalDate.of(2026, 11, 19) to 28.44), m)
    }

    @Test fun keepsOnlyTheMostRecentRows() {
        val m = MarketRepository.parseCboeVixCsv(csv, keepLast = 2)
        assertEquals(setOf(LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 20)), m.keys)
    }

    @Test fun fredWinsAndCboeOnlyFillsGaps() {
        // owner 2026-11-25: Cboe is the backup. FRED had up to 19 Nov; Cboe also had 20 Nov (35.89).
        val fred = mapOf(LocalDate.of(2026, 11, 19) to 28.40, LocalDate.of(2026, 11, 13) to 20.00)
        val cboe = mapOf(LocalDate.of(2026, 11, 20) to 35.89, LocalDate.of(2026, 11, 19) to 28.44)
        val merged = MarketRepository.mergeVix(fred, cboe)
        assertEquals(28.40, merged.getValue(LocalDate.of(2026, 11, 19)), 1e-9)   // FRED's value kept
        assertEquals(35.89, merged.getValue(LocalDate.of(2026, 11, 20)), 1e-9)   // gap filled by Cboe
        assertEquals(20.00, merged.getValue(LocalDate.of(2026, 11, 13)), 1e-9)
        assertFalse(LocalDate.of(2026, 11, 23) in merged)
        assertTrue(merged.size == 3)
    }
}
