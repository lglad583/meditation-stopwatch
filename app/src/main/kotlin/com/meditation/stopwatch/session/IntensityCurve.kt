package com.meditation.stopwatch.session

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Maps elapsed session time to a 0..1 "intensity" that drives both visuals and audio.
 *
 * Design: "build up quickly but subtly".  A saturating exponential reaches ~63% after two minutes,
 * ~86% after four and ~98% after eight – fast enough that the piece is clearly evolving within the
 * first minute, but with a derivative that is always decreasing, so no single moment feels like a
 * jump.  Two slow, incommensurate sine "tides" (periods 97 s and 211 s) are superimposed so the
 * intensity never sits on a plateau; the tides are scaled by the main envelope so the very start
 * is perfectly still.  A 4 s ease-in keeps the first frames black.
 */
object IntensityCurve {
    const val TAU_SECONDS = 120.0

    fun at(elapsedSec: Double): Float {
        if (elapsedSec <= 0.0) return 0f
        val t = elapsedSec
        val env = 1.0 - exp(-t / TAU_SECONDS)
        val easeIn = run { val x = (t / 4.0).coerceIn(0.0, 1.0); x * x * (3 - 2 * x) }
        val tide = 0.035 * sin(2 * PI * t / 97.0) + 0.025 * sin(2 * PI * t / 211.0 + 1.3)
        return (easeIn * (env + env * tide)).coerceIn(0.0, 1.0).toFloat()
    }
}
