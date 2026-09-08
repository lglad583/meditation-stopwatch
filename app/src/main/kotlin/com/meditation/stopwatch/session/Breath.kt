package com.meditation.stopwatch.session

import kotlin.math.ln

enum class BreathStage(val label: String) { INHALE("inhale"), HOLD("hold"), EXHALE("exhale"), REST("rest") }

/**
 * A snapshot of the guided breath at one instant.
 * @param phase     0..1 position inside the current cycle
 * @param fullness  0..1 "how full the lungs are" – a smooth, C1-continuous curve
 * @param velocity  d(fullness)/dt in 1/sec (positive while inhaling)
 * @param stage     which part of the cycle we are in
 * @param cycle     number of completed cycles (breath count)
 * @param periodSec current cycle length in seconds
 */
data class BreathState(
    val phase: Float,
    val fullness: Float,
    val velocity: Float,
    val stage: BreathStage,
    val cycle: Int,
    val periodSec: Float,
) {
    companion object {
        val IDLE = BreathState(0f, 0f, 0f, BreathStage.REST, 0, 10f)
    }
}

/**
 * Pure model of "ideal slow breathing": a coherent-breathing pattern that starts at 6 breaths/min
 * (10 s cycle) and eases to 5 breaths/min (12 s cycle, ~0.083 Hz – the resonance band of the
 * baroreflex) over the first eight minutes of a session.  Exhale is longer than inhale, which
 * favours parasympathetic tone.
 *
 * Everything is a pure function of elapsed session time, so the UI, the GPU renderer and the audio
 * thread can each evaluate it independently and always agree.
 */
object Breath {
    const val PERIOD_START = 10.0       // seconds per breath at t=0   (6 bpm)
    const val PERIOD_END = 12.0         // seconds per breath at t>=RAMP (5 bpm)
    const val RAMP_SECONDS = 8.0 * 60.0 // how long the slow-down takes

    // Fractions of one cycle.
    const val INHALE_END = 0.42
    const val HOLD_END = 0.50
    const val EXHALE_END = 0.94
    // 0.94..1.0 is a short rest at empty lungs.

    fun periodAt(elapsedSec: Double): Double {
        val t = elapsedSec.coerceIn(0.0, RAMP_SECONDS)
        return PERIOD_START + (PERIOD_END - PERIOD_START) * (t / RAMP_SECONDS)
    }

    /** Accumulated cycles: the exact integral of 1/period(t) dt so a changing period never jumps the phase. */
    fun cyclesAt(elapsedSec: Double): Double {
        if (elapsedSec <= 0.0) return 0.0
        val k = (PERIOD_END - PERIOD_START) / RAMP_SECONDS // period slope
        val t = elapsedSec.coerceAtMost(RAMP_SECONDS)
        val rampCycles = ln(periodAt(t) / PERIOD_START) / k
        val tail = (elapsedSec - RAMP_SECONDS).coerceAtLeast(0.0) / PERIOD_END
        return rampCycles + tail
    }

    private fun smooth(x: Double): Double { val c = x.coerceIn(0.0, 1.0); return c * c * (3 - 2 * c) }
    private fun dSmooth(x: Double): Double { val c = x.coerceIn(0.0, 1.0); return 6 * c * (1 - c) }

    /** Lung fullness 0..1 for phase 0..1. Smooth in/out, C1 continuous across stage boundaries. */
    fun fullness(phase: Double): Double {
        val p = ((phase % 1.0) + 1.0) % 1.0
        return when {
            p < INHALE_END -> smooth(p / INHALE_END)
            p < HOLD_END -> 1.0
            p < EXHALE_END -> 1.0 - smooth((p - HOLD_END) / (EXHALE_END - HOLD_END))
            else -> 0.0
        }
    }

    /** d(fullness)/d(phase). */
    fun dFullness(phase: Double): Double {
        val p = ((phase % 1.0) + 1.0) % 1.0
        return when {
            p < INHALE_END -> dSmooth(p / INHALE_END) / INHALE_END
            p < HOLD_END -> 0.0
            p < EXHALE_END -> -dSmooth((p - HOLD_END) / (EXHALE_END - HOLD_END)) / (EXHALE_END - HOLD_END)
            else -> 0.0
        }
    }

    fun stage(phase: Double): BreathStage {
        val p = ((phase % 1.0) + 1.0) % 1.0
        return when {
            p < INHALE_END -> BreathStage.INHALE
            p < HOLD_END -> BreathStage.HOLD
            p < EXHALE_END -> BreathStage.EXHALE
            else -> BreathStage.REST
        }
    }

    fun stateAt(elapsedSec: Double): BreathState {
        if (elapsedSec <= 0.0) return BreathState.IDLE
        val cycles = cyclesAt(elapsedSec)
        val phase = cycles - kotlin.math.floor(cycles)
        val period = periodAt(elapsedSec)
        return BreathState(
            phase = phase.toFloat(),
            fullness = fullness(phase).toFloat(),
            velocity = (dFullness(phase) / period).toFloat(),
            stage = stage(phase),
            cycle = kotlin.math.floor(cycles).toInt(),
            periodSec = period.toFloat(),
        )
    }
}
