package com.meditation.stopwatch

import com.meditation.stopwatch.session.SessionSeed
import com.meditation.stopwatch.visuals.ShaderRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSeedTest {
    @Test fun unitIsInRangeAndSaltSensitive() {
        for (s in longArrayOf(1L, 2L, -5L, 0x123456789L)) for (k in 0 until 50) {
            val u = SessionSeed.unit(s, k)
            assertTrue(u >= 0f && u < 1f)
            assertNotEquals(u, SessionSeed.unit(s, k + 1))
        }
    }

    @Test fun programmeOrderIsAPermutationKeepingTheOpening() {
        val seen = HashSet<String>()
        for (s in 1L..200L) {
            val o = ShaderRenderer.programmeOrder(s, 8)
            assertEquals(0, o[0])
            assertEquals((0 until 8).toSet(), o.toSet())
            seen += o.joinToString(",")
        }
        assertTrue("200 seeds should give many distinct orders, got ${seen.size}", seen.size > 100)
    }
}
