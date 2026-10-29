package com.example.gbpusdpredictor

import com.example.gbpusdpredictor.data.MarketData
import com.example.gbpusdpredictor.repository.PredictionScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionScorerTest {
    private fun row(date: String, rate: Double, predictedRate: Double? = null, predictedPct: Double? = null) = MarketData(
        date = date, capturedAtUtc = "2026-10-01T00:00:00Z",
        ukRate = null, us2y = null, spread2y = null, gbpUsd = rate, oilPrice = null, vix = null,
        spread2yChange1d = null, spread2yChange5d = null, gbpUsdChange5d = null, oilChange5d = null, rsi14 = null,
        predicted14dRate = predictedRate, predicted14dChangePct = predictedPct
    )

    @Test fun aDownCallFollowedByADownMoveIsAHit() {
        val (scored, summary) = PredictionScorer.score(listOf(
            row("2026-10-26", 1.3348, 1.3339, -0.0006),
            row("2026-10-27", 1.3328)
        ))
        assertEquals(1, scored.size)
        assertTrue(scored[0].directionHit)
        assertEquals(0.0011, scored[0].absErrorRate, 1e-9)
        assertEquals(1, summary.directionHits)
        assertEquals(1.0, summary.directionAccuracy!!, 1e-12)
    }

    @Test fun anUpCallFollowedByADownMoveIsAMiss() {
        val (scored, summary) = PredictionScorer.score(listOf(
            row("2026-10-26", 1.3348, 1.3360, 0.0009),
            row("2026-10-27", 1.3328)
        ))
        assertFalse(scored[0].directionHit)
        assertEquals(0, summary.directionHits)
    }

    @Test fun aFridayForecastIsScoredAgainstTheFollowingMonday() {
        val (scored, _) = PredictionScorer.score(listOf(
            row("2026-10-23", 1.3368, 1.3354, -0.001),
            row("2026-10-26", 1.3348)
        ))
        assertEquals(1, scored.size)
        assertEquals("2026-10-26", scored[0].targetDate.toString())
    }

    @Test fun aForecastWithNoLaterReadingIsPendingNotScored() {
        val (scored, summary) = PredictionScorer.score(listOf(row("2026-10-28", 1.3288, 1.3281, -0.0005)))
        assertTrue(scored.isEmpty())
        assertEquals(1, summary.pending)
        assertEquals(null, summary.directionAccuracy)
    }

    @Test fun rowsWithoutAForecastAreIgnored() {
        val (scored, summary) = PredictionScorer.score(listOf(row("2026-10-26", 1.3348), row("2026-10-27", 1.3328)))
        assertTrue(scored.isEmpty())
        assertEquals(0, summary.pending)
    }

    @Test fun medianErrorOfTwoForecastsIsTheirMean() {
        val (_, summary) = PredictionScorer.score(listOf(
            row("2026-10-22", 1.3376, 1.3368, -0.0006), row("2026-10-23", 1.3368, 1.3354, -0.001),
            row("2026-10-26", 1.3348)
        ))
        assertEquals(2, summary.scored)
        assertEquals(summary.meanAbsErrorRate, summary.medianAbsErrorRate, 1e-12)
    }
}
