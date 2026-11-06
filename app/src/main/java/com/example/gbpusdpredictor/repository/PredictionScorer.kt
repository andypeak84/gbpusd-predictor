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

    /** A forecast already made whose target day has no actual rate yet (e.g. today's ECB rate before it is published). */
    data class PendingForecast(
        val forecastDate: LocalDate,
        val targetDate: LocalDate,
        val baseRate: Double,
        val predictedRate: Double,
        val predictedChangePct: Double
    )

    data class Summary(
        val scored: Int,
        val directionHits: Int,
        val meanAbsErrorRate: Double,
        val medianAbsErrorRate: Double,
        /** Forecasts whose target trading day hasn't produced an actual reading yet. */
        val pending: Int,
        /** The same forecasts, so the screen can list them (newest first) instead of only counting them. */
        val pendingForecasts: List<PendingForecast> = emptyList()
    ) {
        val directionAccuracy: Double? get() = if (scored > 0) directionHits.toDouble() / scored else null
    }

    /**
     * Scores the forecasts against what GBP/USD did `horizonDays` business days later.
     *
     * horizonDays == 1 with no `forecastFor` scores the forecast STORED on each row (what the app showed that day). The app only
     * stores the 1-day call, so any other horizon needs `forecastFor`: it re-runs the model on the row's own stored inputs and
     * returns that horizon's predicted change (a fraction, e.g. 0.0012), or null when it cannot. Those multi-day figures are what
     * the current model says about each past day, not a record of what a past screen displayed.
     */
    fun score(
        allRows: List<MarketData>,
        horizonDays: Int = HORIZON_BUSINESS_DAYS,
        forecastFor: ((MarketData) -> Double?)? = null
    ): Pair<List<ScoredForecast>, Summary> {
        val byDate: Map<LocalDate, MarketData> = allRows
            .mapNotNull { row -> runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to row } }
            .toMap()

        val scored = ArrayList<ScoredForecast>()
        var pending = 0
        val pendingList = ArrayList<PendingForecast>()

        for ((forecastDate, row) in byDate) {
            val baseRate = row.gbpUsd ?: continue
            val predictedChangePct: Double
            val predictedRate: Double
            if (forecastFor == null) {
                predictedChangePct = row.predicted14dChangePct ?: continue
                predictedRate = row.predicted14dRate ?: continue
            } else {
                predictedChangePct = forecastFor(row) ?: continue
                predictedRate = baseRate * (1.0 + predictedChangePct)
            }

            val targetDate = AppClock.businessDaysForward(forecastDate, horizonDays)

            val match = (byDate[targetDate]?.let { targetDate to it })
                ?: byDate.entries
                    // On or after the target day only: a holiday gap is covered by the next row, but an EARLIER row would score a
                    // multi-day forecast against a shorter move than it was made for.
                    .filter { it.key.isAfter(forecastDate) && !it.key.isBefore(targetDate) }
                    .map { (d, r) -> Triple(d, r, abs(d.toEpochDay() - targetDate.toEpochDay())) }
                    .minByOrNull { it.third }
                    ?.takeIf { it.third <= TARGET_MATCH_TOLERANCE_DAYS }
                    ?.let { it.first to it.second }

            val actualDate = match?.first
            val actualRate = match?.second?.gbpUsd

            if (actualDate == null || actualRate == null || !actualDate.isAfter(forecastDate)) {
                pending++
                pendingList += PendingForecast(forecastDate, targetDate, baseRate, predictedRate, predictedChangePct)
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
            pending = pending,
            pendingForecasts = pendingList.sortedByDescending { it.forecastDate }
        )
    }
}
