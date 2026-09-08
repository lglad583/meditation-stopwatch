package com.meditation.stopwatch

import com.meditation.stopwatch.audio.SoundDynamics
import com.meditation.stopwatch.audio.SoundId
import com.meditation.stopwatch.session.IntensityCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundDynamicsTest {
    @Test fun neutralWhenIdleAndAtSessionStart() {
        for (i in SoundId.entries.indices) {
            assertEquals(1f, SoundDynamics.drift(i, 0.0, 0f, 42L), 1e-6f)
            assertEquals(1f, SoundDynamics.drift(i, 0.0, 1f, 42L), 1e-6f)
        }
        assertEquals(SoundDynamics.LIFT_MIN_DB, 20 * kotlin.math.log10(SoundDynamics.lift(0f)), 1e-3f)
        assertEquals(SoundDynamics.LIFT_MAX_DB, 20 * kotlin.math.log10(SoundDynamics.lift(1f)), 1e-3f)
    }

    @Test fun driftIsBoundedAndActuallyMoves() {
        for (i in SoundId.entries.indices) {
            var lo = 10f; var hi = 0f
            var t = 0.0
            while (t < 3600) {
                val g = SoundDynamics.drift(i, t, IntensityCurve.at(t), 42L)
                assertTrue(g in 0.5f..1.5f)
                if (g < lo) lo = g; if (g > hi) hi = g
                t += 1.0
            }
            assertTrue("sound $i should drift by at least ±20 %", hi - lo > 0.4f)
        }
    }

    @Test fun soundsDoNotDriftInLockstep() {
        // at any instant the sounds sit at different points of their tides
        var spread = 0f
        var t = 300.0
        while (t < 900) {
            var lo = 10f; var hi = 0f
            for (i in SoundId.entries.indices) { val g = SoundDynamics.drift(i, t, 0.7f, 42L); if (g < lo) lo = g; if (g > hi) hi = g }
            spread = maxOf(spread, hi - lo)
            t += 10.0
        }
        assertTrue(spread > 0.3f)
    }

    @Test fun differentSeedsGiveDifferentJourneys() {
        var diff = 0f
        var t = 200.0
        while (t < 1400) { diff = maxOf(diff, kotlin.math.abs(SoundDynamics.drift(0, t, 0.8f, 1L) - SoundDynamics.drift(0, t, 0.8f, 2L))); t += 5.0 }
        assertTrue(diff > 0.2f)
    }
}
