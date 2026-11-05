package com.example.gbpusdpredictor.utils

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How a forecast's target day is shown. A forecast starts from the latest ECB reference rate and lands on a later ECB rate, so the
 * "1-day" forecast made from yesterday's rate is for TODAY's rate, which the ECB only publishes in the afternoon. Printing the bare
 * date made that look like a forecast for a day that had already begun; this says what the date is.
 */
object ForecastDate {
    private val short = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /** "today's ECB rate, Thu 5 Nov", "tomorrow, Fri 6 Nov", or just "Mon 9 Nov"; days are in AppClock's UTC. */
    fun label(target: LocalDate, today: LocalDate): String {
        val date = target.format(short)
        return when (target) {
            today -> "today's ECB rate, $date"
            today.plusDays(1) -> "tomorrow, $date"
            else -> date
        }
    }
}
