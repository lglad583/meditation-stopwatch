package com.meditation.stopwatch.session

import kotlin.random.Random

/**
 * Per-session randomness.  [SessionClock] draws a fresh 64-bit seed every time a session starts
 * from zero; everything that should differ between sits – shader palettes and layouts, the order
 * of the movements, the phases and periods of the mix drift – derives from it through [unit], so a
 * session is unrepeatable but internally consistent (the GL thread and the audio thread see the
 * same seed).  Sound generators additionally randomise their own RNGs at creation, so even the
 * grain of the rain is never the same twice.
 */
object SessionSeed {
    fun fresh(): Long = Random.nextLong() or 1L   // never zero: a zero seed is the "no session yet" marker

    /** Deterministic hash of ([seed], [salt]) to a float in [0, 1). SplitMix64 finaliser. */
    fun unit(seed: Long, salt: Int): Float {
        var z = seed + salt * -7046029254386353131L   // salt * golden-ratio constant
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        z = z xor (z ushr 31)
        return ((z ushr 40).toInt() and 0xFFFFFF) / 16777216f
    }
}
