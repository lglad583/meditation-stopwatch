package com.meditation.stopwatch.audio

import com.meditation.stopwatch.session.SessionSeed
import kotlin.math.PI
import kotlin.math.sin

/**
 * The living mix: a pure function of session time that makes the soundscape evolve on its own.
 * Two effects are folded into one linear multiplier per sound, applied by the engine on top of
 * the user's slider (so the sliders still set the balance; this sets the journey):
 *
 *  1. **Lift.** The whole mix rises from [LIFT_MIN_DB] at the start of a session to
 *     [LIFT_MAX_DB] at full intensity, so the sound builds along with the visuals.  Auditioning
 *     while idle (intensity 0) is at the quiet end; the slider setting itself is reached a few
 *     minutes in, and the last few decibels arrive over the rest of the hour.
 *  2. **Drift.** Every sound has its own pair of slow, incommensurate sine tides (periods between
 *     45 s and 160 s, distinct per sound) that carry it forward and back in the mix, so no two
 *     sounds move together and the balance is never static.  The drift depth grows with intensity:
 *     about ±1 dB at the start, ±4 dB at full intensity.  It fades in over the first 90 s so the
 *     opening balance is exactly what the user set.
 *
 * Everything is a function of elapsed session time and intensity, so pausing freezes the mix,
 * and idle (elapsed 0) gives every sound the same neutral, steady gain.  Allocation-free.
 */
object SoundDynamics {
    const val LIFT_MIN_DB = -4f
    const val LIFT_MAX_DB = 3f
    private const val DRIFT_DEPTH_MIN = 0.12f
    private const val DRIFT_DEPTH_MAX = 0.42f
    private const val DRIFT_FADE_IN_SEC = 90.0

    /** Two periods per sound, all mutually incommensurate (seconds). Indexed by [SoundId.ordinal]. */
    private val PERIOD_A = doubleArrayOf(71.0, 83.0, 131.0, 97.0, 61.0, 113.0, 79.0, 149.0, 103.0, 137.0, 89.0, 157.0)
    private val PERIOD_B = doubleArrayOf(127.0, 53.0, 47.0, 151.0, 109.0, 67.0, 139.0, 59.0, 73.0, 101.0, 163.0, 107.0)
    private const val TWO_PI = 2 * PI

    /** Linear multiplier common to every sound: the intensity lift. */
    fun lift(intensity: Float): Float {
        val db = LIFT_MIN_DB + (LIFT_MAX_DB - LIFT_MIN_DB) * intensity.coerceIn(0f, 1f)
        return dbToGain(db)
    }

    /**
     * Linear multiplier for sound [index] at [elapsedSec] into the session.  Always in roughly
     * 0.5..1.5 and exactly 1 at elapsed 0.  Does not include [lift].  The session [seed] sets the
     * phase of each tide and stretches its period by up to ±20 %, so the journey of the mix is
     * different every sit while staying a pure function of session time.
     */
    fun drift(index: Int, elapsedSec: Double, intensity: Float, seed: Long): Float {
        if (elapsedSec <= 0.0) return 1f
        val i = index % PERIOD_A.size
        val fadeIn = run { val x = (elapsedSec / DRIFT_FADE_IN_SEC).coerceIn(0.0, 1.0); x * x * (3 - 2 * x) }
        val depth = (DRIFT_DEPTH_MIN + (DRIFT_DEPTH_MAX - DRIFT_DEPTH_MIN) * intensity.coerceIn(0f, 1f)) * fadeIn
        val pa = PERIOD_A[i] * (0.8 + 0.4 * SessionSeed.unit(seed, i * 4 + 0))
        val pb = PERIOD_B[i] * (0.8 + 0.4 * SessionSeed.unit(seed, i * 4 + 1))
        val a = sin(TWO_PI * (elapsedSec / pa + SessionSeed.unit(seed, i * 4 + 2)))
        val b = sin(TWO_PI * (elapsedSec / pb + SessionSeed.unit(seed, i * 4 + 3)))
        return (1.0 + depth * (0.65 * a + 0.35 * b)).toFloat()
    }

    /** [lift] × [drift]: the full multiplier the engine applies to sound [index]. */
    fun gain(index: Int, elapsedSec: Double, intensity: Float, seed: Long): Float =
        lift(intensity) * drift(index, elapsedSec, intensity, seed)
}
