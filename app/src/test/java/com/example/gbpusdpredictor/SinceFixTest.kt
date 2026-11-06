package com.example.gbpusdpredictor

import com.example.gbpusdpredictor.utils.SinceFix
import org.junit.Assert.assertEquals
import org.junit.Test

class SinceFixTest {
    @Test fun partWayToTheForecast() {
        assertEquals(
            "About 50% of the forecast move has already happened: live +0.10% of a forecast +0.20%.",
            SinceFix.describe(0.001, 0.002)
        )
    }

    @Test fun liveAlreadyPastTheForecast() {
        assertEquals(
            "Live has already reached the forecast: +0.55% since the reference, the forecast move is +0.20%.",
            SinceFix.describe(0.0055, 0.002)
        )
    }

    @Test fun downForecastAndLiveDownToo() {
        assertEquals(
            "About 50% of the forecast move has already happened: live -0.10% of a forecast -0.20%.",
            SinceFix.describe(-0.001, -0.002)
        )
    }

    @Test fun liveMovedTheOtherWay() {
        assertEquals(
            "Live has moved the other way since the reference: -0.10%, against a forecast of +0.20%.",
            SinceFix.describe(-0.001, 0.002)
        )
    }

    @Test fun aFlatForecastGivesNoShare() {
        assertEquals(
            "The forecast is close to flat; live has moved +0.30% since the reference.",
            SinceFix.describe(0.003, 0.0002)
        )
    }

    @Test fun liveLevelWithTheReference() {
        assertEquals(
            "Live is level with the reference, so none of the forecast move has happened yet.",
            SinceFix.describe(0.00005, 0.002)
        )
    }
}
