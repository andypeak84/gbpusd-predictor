package com.example.gbpusdpredictor.repository

import com.example.gbpusdpredictor.data.MarketData
import com.example.gbpusdpredictor.utils.AppClock
import java.time.LocalDate
import kotlin.math.abs

/**
 * Scores each stored 1-day-ahead forecast against what GBP/USD actually did next.
 *
 * Every market_data row carries `predicted14dChangePct` / `predicted14dRate` -- despite
 * the legacy "14d" name, that's the model's call for MarketRepository.DEFAULT_HORIZON_DAYS
 * business day(s) ahead of that row's own `date` (currently 1). Until now the app only
 * ever showed the *next* forecast, which by definition can't be checked yet. This walks
 * every past row, finds the ACTUAL gbpUsd on (or nearest within tolerance to) that
 * forecast's target trading day, and reports predicted-vs-actual plus a running
 * directional hit-rate and error, so there's a real track record to look at.
 *
 * Pure read logic -- nothing here writes. Feed it marketDao().getAllEntries().
 */
object PredictionScorer {

    /** Must match MarketRepository.DEFAULT_HORIZON_DAYS -- the horizon refreshData() stores
     * the per-row prediction for. Kept as its own constant rather than importing so this
     * stays a self-contained pure helper. */
    private const val HORIZON_BUSINESS_DAYS = 1

    /** UK/US trading calendars differ and a source can lag a day; accept an actual reading
     * up to this many calendar days off the exact target date, nearest wins. */
    private const val TARGET_MATCH_TOLERANCE_DAYS = 2L

    data class ScoredForecast(
        val forecastDate: LocalDate,      // the row the prediction was made from
        val targetDate: LocalDate,        // forecastDate + HORIZON_BUSINESS_DAYS business days
        val matchedActualDate: LocalDate, // the actual row scored against (within tolerance)
        val baseRate: Double,             // gbpUsd on forecastDate
        val predictedRate: Double,
        val actualRate: Double,
        val predictedChangePct: Double,
        val actualChangePct: Double
    ) {
        val absErrorRate: Double get() = abs(predictedRate - actualRate)
        /** Did the forecast get the direction right? Zero counts as "up" on both sides, so
         * a flat call scored against a flat outcome is a hit. */
        val directionHit: Boolean get() = (predictedChangePct >= 0.0) == (actualChangePct >= 0.0)
    }

    data class Summary(
        val scored: Int,
        val directionHits: Int,
        val meanAbsErrorRate: Double,
        val medianAbsErrorRate: Double,
        /** Forecasts whose target trading day hasn't produced an actual reading yet. */
        val pending: Int
    ) {
        val directionAccuracy: Double? get() = if (scored > 0) directionHits.toDouble() / scored else null
    }

    fun score(allRows: List<MarketData>): Pair<List<ScoredForecast>, Summary> {
        val byDate: Map<LocalDate, MarketData> = allRows
            .mapNotNull { row -> runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to row } }
            .toMap()

        val scored = ArrayList<ScoredForecast>()
        var pending = 0

        for ((forecastDate, row) in byDate) {
            val predictedChangePct = row.predicted14dChangePct ?: continue
            val predictedRate = row.predicted14dRate ?: continue
            val baseRate = row.gbpUsd ?: continue

            val targetDate = AppClock.businessDaysForward(forecastDate, HORIZON_BUSINESS_DAYS)

            val match = (byDate[targetDate]?.let { targetDate to it })
                ?: byDate.entries
                    .filter { it.key.isAfter(forecastDate) }
                    .map { (d, r) -> Triple(d, r, abs(d.toEpochDay() - targetDate.toEpochDay())) }
                    .minByOrNull { it.third }
                    ?.takeIf { it.third <= TARGET_MATCH_TOLERANCE_DAYS }
                    ?.let { it.first to it.second }

            val actualDate = match?.first
            val actualRate = match?.second?.gbpUsd

            if (actualDate == null || actualRate == null || !actualDate.isAfter(forecastDate)) {
                pending++
                continue
            }

            scored += ScoredForecast(
                forecastDate = forecastDate,
                targetDate = targetDate,
                matchedActualDate = actualDate,
                baseRate = baseRate,
                predictedRate = predictedRate,
                actualRate = actualRate,
                predictedChangePct = predictedChangePct,
                actualChangePct = (actualRate - baseRate) / baseRate
            )
        }

        scored.sortByDescending { it.forecastDate }

        val errors = scored.map { it.absErrorRate }.sorted()
        val mean = if (errors.isEmpty()) 0.0 else errors.average()
        val median = when {
            errors.isEmpty() -> 0.0
            errors.size % 2 == 1 -> errors[errors.size / 2]
            else -> (errors[errors.size / 2 - 1] + errors[errors.size / 2]) / 2.0
        }

        return scored to Summary(
            scored = scored.size,
            directionHits = scored.count { it.directionHit },
            meanAbsErrorRate = mean,
            medianAbsErrorRate = median,
            pending = pending
        )
    }
}
