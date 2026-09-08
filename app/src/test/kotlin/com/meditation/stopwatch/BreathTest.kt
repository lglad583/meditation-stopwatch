package com.meditation.stopwatch

import com.meditation.stopwatch.session.Breath
import com.meditation.stopwatch.session.IntensityCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BreathTest {
    @Test fun fullnessIsContinuous() {
        var prev = Breath.fullness(0.0)
        var i = 1
        while (i <= 2000) {
            val f = Breath.fullness(i / 2000.0)
            assertTrue("jump at ${i / 2000.0}", abs(f - prev) < 0.01)
            prev = f; i++
        }
    }
    @Test fun phaseIsMonotonicAndContinuousAcrossRamp() {
        var prev = 0.0
        var t = 0.0
        while (t < Breath.RAMP_SECONDS + 60) {
            val c = Breath.cyclesAt(t)
            assertTrue(c >= prev); assertTrue(c - prev < 0.02)
            prev = c; t += 0.1
        }
        // slope after the ramp is exactly 1/PERIOD_END
        val a = Breath.cyclesAt(Breath.RAMP_SECONDS + 100); val b = Breath.cyclesAt(Breath.RAMP_SECONDS + 112)
        assertEquals(1.0, b - a, 1e-6)
    }
    @Test fun intensityRampsQuicklyButStaysBounded() {
        assertEquals(0f, IntensityCurve.at(0.0), 1e-6f)
        assertTrue(IntensityCurve.at(60.0) > 0.3f)
        assertTrue(IntensityCurve.at(240.0) > 0.75f)
        var t = 0.0
        while (t < 3600) { val v = IntensityCurve.at(t); assertTrue(v in 0f..1f); t += 0.5 }
    }
}
