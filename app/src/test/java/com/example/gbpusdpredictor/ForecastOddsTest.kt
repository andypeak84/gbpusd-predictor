package com.example.gbpusdpredictor

import com.example.gbpusdpredictor.utils.ForecastOdds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastOddsTest {
    @Test fun normalCdfMatchesKnownValues() {
        assertEquals(0.5, ForecastOdds.cdf(0.0), 1e-6)
        assertEquals(0.8413, ForecastOdds.cdf(1.0), 1e-4)
        assertEquals(0.9772, ForecastOdds.cdf(2.0), 1e-4)
        assertEquals(1.0 - ForecastOdds.cdf(1.3), ForecastOdds.cdf(-1.3), 1e-9)
    }

    // 23 Nov 2026: reference 1.3074, live 1.3020, 7-day forecast +1.01%, typical 7-day move 1.098%, average miss 0.0091 / 1.3206
    private val ref = 1.3074
    private val fc = 0.0101
    private val sd = 0.01098
    private val mae = 0.0091 / 1.3206

    @Test fun theWorkedExampleMatchesTheHandCalculation() {
        val o = ForecastOdds.compute(fc, ref, startRate = 1.3020, horizonSd = sd, maeFraction = mae)!!
        assertEquals(0.40, o.higher, 0.02)   // about 40% above the reference
        assertEquals(0.26, o.inBand, 0.02)   // about 26% inside the band
        assertEquals(0.12, o.reach, 0.02)    // about 12% to reach 1.3206
    }

    @Test fun startingFromTheReferenceGivesTheHigherChances() {
        val o = ForecastOdds.compute(fc, ref, startRate = ref, horizonSd = sd, maeFraction = mae)!!
        assertEquals(0.55, o.higher, 0.02)
        assertEquals(0.36, o.inBand, 0.02)
        assertEquals(0.21, o.reach, 0.02)
    }

    @Test fun aFlatForecastFromTheReferenceIsAFiftyFiftyCall() {
        val o = ForecastOdds.compute(0.0, ref, ref, sd, mae)!!
        assertEquals(0.5, o.higher, 1e-6)
        assertEquals(0.5, o.reach, 1e-6)
    }

    @Test fun aFallForecastMirrorsARise() {
        val up = ForecastOdds.compute(0.0101, ref, ref, sd, mae)!!
        val down = ForecastOdds.compute(-0.0101, ref, ref, sd, mae)!!
        assertEquals(up.higher, 1.0 - down.higher, 1e-9)
        assertTrue(down.reach in 0.0..1.0)
        // reaching a fall target means ending at or below it
        assertEquals(up.reach, down.reach, 0.01)
    }

    @Test fun aWiderBandHoldsMoreOfTheOutcomes() {
        val narrow = ForecastOdds.compute(fc, ref, ref, sd, 0.003)!!
        val wide = ForecastOdds.compute(fc, ref, ref, sd, 0.02)!!
        assertTrue(wide.inBand > narrow.inBand)
    }

    @Test fun badInputsGiveNoNumber() {
        assertNull(ForecastOdds.compute(fc, 0.0, ref, sd, mae))
        assertNull(ForecastOdds.compute(fc, ref, ref, 0.0, mae))
    }

    @Test fun theTextReadsNaturally() {
        val o = ForecastOdds.Odds(0.55, 0.36, 0.21)
        assertEquals(
            "higher than the reference 55% · inside the forecast ± band 36% · reach 1.3206 or more 21%",
            ForecastOdds.describe(o, 1.3206, rising = true)
        )
    }
}
