package com.meditation.stopwatch.audio.generators

import com.meditation.stopwatch.audio.Lfo
import com.meditation.stopwatch.audio.Ramp
import com.meditation.stopwatch.audio.RenderContext
import com.meditation.stopwatch.audio.SoundGenerator
import com.meditation.stopwatch.audio.SoundId
import kotlin.math.PI
import kotlin.math.sin

/**
 * Binaural theta entrainment: 200 Hz in the left ear, 206 Hz in the right, so the brain perceives a
 * 6 Hz beat (theta band).  Headphones only – the two channels are deliberately different.
 *
 * The signal is kept surgically clean: two Double phase accumulators wrapped every cycle (no drift,
 * no precision loss), true `sin`, a soft second harmonic at -24 dB for warmth, and every gain
 * change is interpolated per sample.  The only movement is a ±10 % amplitude "breath" at 0.05 Hz
 * (a 20 s cycle) and a 2 s smoothstep fade-in on the first render after [reset].
 *
 * Cost: four `sin` calls per frame – trivial for the CPU.
 */
class BinauralGenerator : SoundGenerator {
    override val id: SoundId = SoundId.BINAURAL

    private companion object {
        const val LEFT_HZ = 200.0
        const val RIGHT_HZ = 206.0
        /** -22 dBFS RMS for a sine: amplitude = 10^(-22/20) * sqrt(2). */
        const val LEVEL = 0.1123f
        /** Second harmonic, -24 dB relative to the fundamental. */
        const val H2 = 0.0631
        const val BREATH_HZ = 0.05f
        const val BREATH_DEPTH = 0.10f
        const val FADE_IN_SEC = 2.0f
        const val TWO_PI = 2.0 * PI
    }

    private var sr = 48_000
    private var phL = 0.0
    private var phR = 0.0
    private var incL = 0.0
    private var incR = 0.0
    private val breath = Lfo(BREATH_HZ)
    private val gain = Ramp(0f)
    /** 0..1 fade-in progress; the curve actually applied is smoothstep(fade). */
    private var fade = 0f
    private var fadeStep = 0f

    override fun reset(sampleRate: Int) {
        sr = sampleRate
        incL = TWO_PI * LEFT_HZ / sr
        incR = TWO_PI * RIGHT_HZ / sr
        phL = 0.0
        phR = 0.0
        fade = 0f
        fadeStep = 1f / (FADE_IN_SEC * sr)
        gain.set(LEVEL)
    }

    override fun render(out: FloatArray, frames: Int, ctx: RenderContext) {
        // Block-rate modulation, linearly interpolated across the block: at 0.05 Hz one block moves
        // the target by ~1e-5, far below anything audible as zipper noise.
        gain.target(LEVEL * (1f + BREATH_DEPTH * breath.nextBlock(frames, sr)), frames)

        val iL = incL
        val iR = incR
        var pl = phL
        var pr = phR
        var f = fade
        var i = 0
        for (n in 0 until frames) {
            if (f < 1f) { f += fadeStep; if (f > 1f) f = 1f }
            val env = (gain.next() * (f * f * (3f - 2f * f))).toDouble()
            val l = sin(pl) + H2 * sin(pl + pl)
            val r = sin(pr) + H2 * sin(pr + pr)
            out[i] = (l * env).toFloat()
            out[i + 1] = (r * env).toFloat()
            i += 2
            pl += iL; if (pl >= TWO_PI) pl -= TWO_PI
            pr += iR; if (pr >= TWO_PI) pr -= TWO_PI
        }
        phL = pl
        phR = pr
        fade = f
    }
}
