package com.meditation.stopwatch.audio.generators

import com.meditation.stopwatch.audio.Biquad
import com.meditation.stopwatch.audio.DcBlock
import com.meditation.stopwatch.audio.FastRandom
import com.meditation.stopwatch.audio.OnePole
import com.meditation.stopwatch.audio.Ramp
import com.meditation.stopwatch.audio.RenderContext
import com.meditation.stopwatch.audio.PinkFilter
import com.meditation.stopwatch.audio.SoundGenerator
import com.meditation.stopwatch.audio.SoundId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/*
 * The three noise colours.
 *
 * They are deliberately plain: a steady, wide, gaussian bed with nothing periodic the ear could
 * latch on to.  All three are level matched at about -20 dBFS RMS (peaks stay under ~0.45) so
 * switching between them feels equally loud, and each channel pair is built from independent draws
 * so the image is diffuse rather than a mono blob in the middle of the head.
 *
 * Control values (the tiny intensity trims, the white cutoff) are refreshed at most once per
 * NOISE_BLOCK samples regardless of the host buffer size and are applied through Ramp / OnePole,
 * so nothing can step.  Session intensity is used only for barely-there evolution: the white
 * noise softens its top end by ~8 %, pink and brown gain about half a decibel.
 */
private const val NOISE_BLOCK = 256

private const val WHITE_CUTOFF_HZ = 9000f
private const val WHITE_LP_Q = 0.707f
/** Gaussian white through the 9 kHz Butterworth keeps ~42 % of its power; this lands at -20 dBFS RMS. */
private const val WHITE_GAIN = 0.155f

private const val PINK_TOP_HZ = 10_000f
/** Kellet pink from unit-variance white measures ~0.44 RMS (after the 10 kHz shelf); this lands at -20 dBFS. */
private const val PINK_GAIN = 0.228f

/** Corner of the leaky integrator: flat below, -6 dB/oct above. */
private const val BROWN_CORNER_HZ = 12f
/** RMS of the integrator output is normalised to 1 in reset(); the DC blocker then removes ~45 % of the power. */
private const val BROWN_GAIN = 0.134f
/** Amplitude of the component shared by both channels (correlation ≈ 0.12): a solid centre, still wide. */
private const val BROWN_SHARED = 0.35f

/** White noise: gaussian, independent per channel, gently low-passed so it never fatigues. */
class WhiteNoiseGenerator : SoundGenerator {
    override val id: SoundId get() = SoundId.WHITE_NOISE

    private val rng = FastRandom(0x2A4B6C8D1E3F5A7BL)
    private val lpL = Biquad()
    private val lpR = Biquad()
    private val intensity = OnePole(0.02f)
    private var sr = 0
    /** Cutoff the biquads are currently programmed with; recomputed only when the target moves. */
    private var cutoff = 0f

    override fun reset(sampleRate: Int) {
        sr = sampleRate
        lpL.reset()
        lpR.reset()
        intensity.set(0f)
        program(WHITE_CUTOFF_HZ)
    }

    private fun program(hz: Float) {
        cutoff = hz
        lpL.lowpass(hz, WHITE_LP_Q, sr)
        lpR.lowpass(hz, WHITE_LP_Q, sr)
    }

    override fun render(out: FloatArray, frames: Int, ctx: RenderContext) {
        if (sr == 0) reset(ctx.sampleRate)
        var pos = 0
        while (pos < frames) {
            val n = minOf(NOISE_BLOCK, frames - pos)
            // Evolution: the top end eases down by up to 8 % over the session (felt, not heard).
            // Intensity is smoothed at block rate and the filter is only re-programmed for moves
            // of more than 20 Hz, so this costs a handful of coefficient updates per session.
            val target = WHITE_CUTOFF_HZ * (1f - 0.08f * intensity.process(ctx.intensity))
            if (abs(target - cutoff) > 20f) program(target)
            var i = pos * 2
            val end = (pos + n) * 2
            while (i < end) {
                out[i] = lpL.process(rng.nextGaussian()) * WHITE_GAIN
                out[i + 1] = lpR.process(rng.nextGaussian()) * WHITE_GAIN
                i += 2
            }
            pos += n
        }
    }
}

/** Pink noise: Kellet filter on gaussian white, DC blocked, top end rounded off at 10 kHz. */
class PinkNoiseGenerator : SoundGenerator {
    override val id: SoundId get() = SoundId.PINK_NOISE

    private val rng = FastRandom(0x3F1C7E9A5B2D4C61L)
    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val dcL = DcBlock(0.999f)
    private val dcR = DcBlock(0.999f)
    private val topL = OnePole()
    private val topR = OnePole()
    private val gain = Ramp(PINK_GAIN)
    private val intensity = OnePole(0.02f)
    private var sr = 0

    override fun reset(sampleRate: Int) {
        sr = sampleRate
        topL.setCutoff(PINK_TOP_HZ, sr)
        topR.setCutoff(PINK_TOP_HZ, sr)
        topL.set(0f)
        topR.set(0f)
        gain.set(PINK_GAIN)
        intensity.set(0f)
    }

    override fun render(out: FloatArray, frames: Int, ctx: RenderContext) {
        if (sr == 0) reset(ctx.sampleRate)
        var pos = 0
        while (pos < frames) {
            val n = minOf(NOISE_BLOCK, frames - pos)
            // +0.5 dB at full intensity: a whisper of growth, ramped so it can never step.
            gain.target(PINK_GAIN * (1f + 0.06f * intensity.process(ctx.intensity)), n)
            var i = pos * 2
            val end = (pos + n) * 2
            while (i < end) {
                val g = gain.next()
                out[i] = topL.process(dcL.process(pinkL.process(rng.nextGaussian()))) * g
                out[i + 1] = topR.process(dcR.process(pinkR.process(rng.nextGaussian()))) * g
                i += 2
            }
            pos += n
        }
    }
}

/**
 * Brown noise: white through a leaky integrator (-6 dB/oct above [BROWN_CORNER_HZ]), then a DC
 * blocker so the random walk can never drift.  Left and right integrate mostly independent noise
 * plus a small shared term, which anchors the low end in the centre without collapsing the width.
 */
class BrownNoiseGenerator : SoundGenerator {
    override val id: SoundId get() = SoundId.BROWN_NOISE

    private val rng = FastRandom(0x17D9B3E5F0A2C486L)
    private val dcL = DcBlock(0.999f)
    private val dcR = DcBlock(0.999f)
    private val gain = Ramp(BROWN_GAIN)
    private val intensity = OnePole(0.02f)
    private var sr = 0
    private var yL = 0f
    private var yR = 0f
    /** Integrator pole; 1 - 2π·fc/sr. */
    private var leak = 0f
    /** Input scale that makes the integrator output unit RMS for unit-variance input: sqrt(1 - leak²). */
    private var drive = 0f
    private val indep = sqrt(1f - BROWN_SHARED * BROWN_SHARED)

    override fun reset(sampleRate: Int) {
        sr = sampleRate
        leak = exp(-6.2831855f * BROWN_CORNER_HZ / sr)
        drive = sqrt(1f - leak * leak)
        yL = 0f
        yR = 0f
        gain.set(BROWN_GAIN)
        intensity.set(0f)
    }

    override fun render(out: FloatArray, frames: Int, ctx: RenderContext) {
        if (sr == 0) reset(ctx.sampleRate)
        var pos = 0
        while (pos < frames) {
            val n = minOf(NOISE_BLOCK, frames - pos)
            gain.target(BROWN_GAIN * (1f + 0.06f * intensity.process(ctx.intensity)), n)
            val lk = leak
            val dr = drive
            var l = yL
            var r = yR
            var i = pos * 2
            val end = (pos + n) * 2
            while (i < end) {
                val shared = rng.nextGaussian() * BROWN_SHARED
                l = l * lk + (rng.nextGaussian() * indep + shared) * dr
                r = r * lk + (rng.nextGaussian() * indep + shared) * dr
                val g = gain.next()
                out[i] = dcL.process(l) * g
                out[i + 1] = dcR.process(r) * g
                i += 2
            }
            yL = l
            yR = r
            pos += n
        }
    }
}
