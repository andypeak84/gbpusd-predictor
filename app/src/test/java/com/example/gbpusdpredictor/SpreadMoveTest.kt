package com.example.gbpusdpredictor

import com.example.gbpusdpredictor.utils.SpreadMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpreadMoveTest {
    @Test fun theTwentyFifthOfNovemberExample() {
        // spread 0.050 now, model input -1695.40% -> the spread was about -0.0031, so it rose about 0.053 points
        assertEquals(0.0531, SpreadMove.points(0.050, -16.954)!!, 0.0005)
    }

    @Test fun anOrdinaryChange() {
        // from 0.50 to 0.55 is +10% and +0.05 points
        assertEquals(0.05, SpreadMove.points(0.55, 0.10)!!, 1e-9)
    }

    @Test fun missingValuesGiveNothing() {
        assertNull(SpreadMove.points(null, 0.1))
        assertNull(SpreadMove.points(0.05, null))
        assertNull(SpreadMove.points(0.05, -1.0))
    }
}
