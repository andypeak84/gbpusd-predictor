package com.example.gbpusdpredictor.utils

import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The app's single definition of "today," anchored to UTC rather than the device's local
 * timezone. Financial data providers (FRED, Frankfurter) publish on a schedule tied to UTC
 * / US market hours, not to whatever timezone a given phone happens to be set to -- using
 * device-local time for date-stamping meant a user far from UTC (e.g. UTC+7) would see
 * "today" begin many hours before the data sources' own day actually rolled over, which
 * showed up as confusing apparent data "freezes."
 *
 * Every date used for storage keys, lookups, or "is this from today" checks should go
 * through this rather than calling LocalDate.now() directly, so the whole app agrees on
 * one consistent definition regardless of device locale/timezone settings.
 */
object AppClock {
    fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)

    /**
     * Steps back `days` full business days from `from`, skipping Saturday/Sunday.
     * Weekday-only, same simplification `pd.bdate_range` already uses on the training
     * side (ml/train_model.py) -- doesn't account for bank holidays, but every live fetch
     * is verified against the source's own reported date for this exact target date
     * anyway, so a holiday just naturally comes back with no match (null) rather than
     * silently drifting.
     */
    fun businessDaysBack(from: LocalDate, days: Int): LocalDate {
        var candidate = from
        var remaining = days
        while (remaining > 0) {
            candidate = candidate.minusDays(1)
            if (candidate.dayOfWeek != java.time.DayOfWeek.SATURDAY && candidate.dayOfWeek != java.time.DayOfWeek.SUNDAY) {
                remaining--
            }
        }
        return candidate
    }

    /** Forward counterpart of [businessDaysBack] -- steps FORWARD `days` full business
     * days from `from`, skipping Saturday/Sunday. Used to turn a forecast's origin date
     * into the trading day it's actually a prediction FOR (PredictionScorer). */
    fun businessDaysForward(from: LocalDate, days: Int): LocalDate {
        var candidate = from
        var remaining = days
        while (remaining > 0) {
            candidate = candidate.plusDays(1)
            if (candidate.dayOfWeek != java.time.DayOfWeek.SATURDAY && candidate.dayOfWeek != java.time.DayOfWeek.SUNDAY) {
                remaining--
            }
        }
        return candidate
    }

    /** Count of business days (Mon-Fri, no bank-holiday awareness) strictly between the
     * two dates, sign-less. `businessDaysBetween(Fri, next Mon) == 1`. Used by the home
     * screen's staleness check to measure how far the newest stored row is behind the
     * date the daily refresh should have reached by now. */
    fun businessDaysBetween(a: LocalDate, b: LocalDate): Int {
        val start = if (a.isBefore(b)) a else b
        val end = if (a.isBefore(b)) b else a
        var count = 0
        var cursor = start
        while (cursor.isBefore(end)) {
            cursor = cursor.plusDays(1)
            if (cursor.dayOfWeek != java.time.DayOfWeek.SATURDAY && cursor.dayOfWeek != java.time.DayOfWeek.SUNDAY) {
                count++
            }
        }
        return count
    }
}
