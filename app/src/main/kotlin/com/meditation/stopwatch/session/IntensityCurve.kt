package com.meditation.stopwatch.session

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Maps elapsed session time to a 0..1 "intensity" that drives both visuals and audio.
 *
 * Design: "get going quickly, then never stop building".  Two saturating exponentials are summed:
 *
 *  - a fast one (τ = 60 s, weight 0.62) that makes the piece clearly evolve within the first
 *    half minute and carries it past half by two minutes;
 *  - a slow one (τ = 20 min, weight 0.38) that keeps the intensity climbing for the whole session –
 *    roughly a tenth every ten minutes through the middle of an hour – so a long sit keeps deepening
 *    instead of parking on a plateau after six minutes.
 *
 * Approximate values: 0.41 at 1 min, 0.57 at 2, 0.67 at 4, 0.77 at 10, 0.86 at 20, 0.92 at 30,
 * 0.98 at 60.  The derivative is always positive and always decreasing, so no moment feels like a
 * step.  Two slow, incommensurate sine "tides" (periods 97 s and 211 s) are superimposed so the
 * intensity never sits still even locally; they are scaled by the envelope so the very start is
 * perfectly calm.  A 4 s ease-in keeps the first frames black.
 */
object IntensityCurve {
    const val FAST_TAU_SECONDS = 60.0
    const val SLOW_TAU_SECONDS = 1200.0
    private const val FAST_WEIGHT = 0.62
    private const val SLOW_WEIGHT = 1.0 - FAST_WEIGHT

    fun at(elapsedSec: Double): Float {
        if (elapsedSec <= 0.0) return 0f
        val t = elapsedSec
        val env = FAST_WEIGHT * (1.0 - exp(-t / FAST_TAU_SECONDS)) + SLOW_WEIGHT * (1.0 - exp(-t / SLOW_TAU_SECONDS))
        val easeIn = run { val x = (t / 4.0).coerceIn(0.0, 1.0); x * x * (3 - 2 * x) }
        val tide = 0.035 * sin(2 * PI * t / 97.0) + 0.025 * sin(2 * PI * t / 211.0 + 1.3)
        return (easeIn * (env + env * tide)).coerceIn(0.0, 1.0).toFloat()
    }
}
