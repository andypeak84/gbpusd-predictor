package com.example.gbpusdpredictor

import com.example.gbpusdpredictor.utils.ForecastDate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ForecastDateTest {
    private val thursday = LocalDate.of(2026, 11, 5)

    @Test fun aTargetOfTodayIsCalledTodaysRate() {
        assertEquals("today's ECB rate, Thu 5 Nov", ForecastDate.label(thursday, thursday))
    }

    @Test fun aTargetOfTomorrowIsCalledTomorrow() {
        assertEquals("tomorrow, Fri 6 Nov", ForecastDate.label(thursday.plusDays(1), thursday))
    }

    @Test fun laterTargetsKeepJustTheDate() {
        assertEquals("Mon 9 Nov", ForecastDate.label(LocalDate.of(2026, 11, 9), thursday))
    }

    @Test fun fridayRunForMondayIsNotCalledTomorrow() {
        val friday = LocalDate.of(2026, 11, 6)
        assertEquals("Mon 9 Nov", ForecastDate.label(LocalDate.of(2026, 11, 9), friday))
    }
}
