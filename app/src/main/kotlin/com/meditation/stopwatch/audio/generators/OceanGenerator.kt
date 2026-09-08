package com.meditation.stopwatch.audio.generators

import com.meditation.stopwatch.audio.Biquad
import com.meditation.stopwatch.audio.FastRandom
import com.meditation.stopwatch.audio.Lfo
import com.meditation.stopwatch.audio.OnePole
import com.meditation.stopwatch.audio.PinkFilter
import com.meditation.stopwatch.audio.Ramp
import com.meditation.stopwatch.audio.RenderContext
import com.meditation.stopwatch.audio.SoundGenerator
import com.meditation.stopwatch.audio.SoundId
import kotlin.math.abs
import kotlin.math.exp

/*
 * Ocean: waves that follow the breath.
 *
 * The swell is a single 0..1 control.  While the stopwatch runs it is lung fullness (smoothed over
 * ~150 ms so a coarse UI tick never steps it); while idle a slow LFO (period ~11 s) stands in, so
 * the sea still moves when the user is only auditioning.  Three layers ride on it:
 *
 *  (a) The deep: pink noise per channel through a 220 Hz lowpass – the constant body of the sea,
 *      barely modulated (±15 %) so there is always something underneath.
 *  (b) The wash: pink noise through a lowpass whose cutoff climbs from ~350 Hz at empty lungs to
 *      ~2.4 kHz at full, with the gain rising on a squared curve.  The rising cutoff is what makes
 *      an inhale sound like a wave building and breaking rather than a fader going up.
 *  (c) Foam: white noise through a 3 kHz highpass, gated by the top of the swell (smoothstep from
 *      0.55 to 1.0, cubed), so it only hisses at the crest.
 *
 * A slow L/R balance drift (period ~45 s) keeps the image alive; intensity opens the wash cutoff
 * by up to 20 % and adds a little foam.  Coefficients are re-programmed only for moves > 1 %.
 */
private const val OCEAN_BLOCK = 256
/** Pink → 220 Hz lowpass has RMS ≈ 0.10; this sits near -30 dBFS. */
private const val OCEAN_DEEP_GAIN = 0.30f
/** Pink → ~1.2 kHz lowpass has RMS ≈ 0.19; the wash peaks near -22 dBFS at full lungs. */
private const val OCEAN_WASH_GAIN = 0.34f
private const val OCEAN_FOAM_GAIN = 0.09f

class OceanGenerator : SoundGenerator {
    override val id: SoundId get() = SoundId.OCEAN

    private val rng = FastRandom(0x0CEA4B17D93E52F1L xor System.nanoTime())
    private var sr = 0

    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val deepL = Biquad()
    private val deepR = Biquad()
    private val washL = Biquad()
    private val washR = Biquad()
    private val foamL = Biquad()
    private val foamR = Biquad()
    private var washCutoff = 0f

    private val deepGL = Ramp()
    private val deepGR = Ramp()
    private val washGL = Ramp()
    private val washGR = Ramp()
    private val foamG = Ramp()

    private val swell = OnePole()
    private val intensity = OnePole(0.02f)
    private val idleWave = Lfo(0.09f, 0.25f)
    private val balance = Lfo(0.022f, 0.6f)
    private val deepUnd = Lfo(0.041f, 0.1f)

    override fun reset(sampleRate: Int) {
        sr = sampleRate
        deepL.reset(); deepR.reset(); washL.reset(); washR.reset(); foamL.reset(); foamR.reset()
        deepL.lowpass(220f, 0.7f, sr)
        deepR.lowpass(235f, 0.7f, sr)
        foamL.highpass(3000f, 0.6f, sr)
        foamR.highpass(3300f, 0.6f, sr)
        washCutoff = 0f
        programWash(600f)
        deepGL.set(0f); deepGR.set(0f); washGL.set(0f); washGR.set(0f); foamG.set(0f)
        swell.set(0.3f)
        swell.setCutoff(1.1f, sr / OCEAN_BLOCK)   // ~150 ms at block rate
        intensity.set(0f)
    }

    private fun programWash(hz: Float) {
        washCutoff = hz
        washL.lowpass(hz, 0.75f, sr)
        washR.lowpass(hz * 1.06f, 0.75f, sr)
    }

    override fun render(out: FloatArray, frames: Int, ctx: RenderContext) {
        if (sr == 0) reset(ctx.sampleRate)
        var pos = 0
        while (pos < frames) {
            val n = minOf(OCEAN_BLOCK, frames - pos)
            updateControls(n, ctx)
            var i = pos * 2
            val end = (pos + n) * 2
            while (i < end) {
                val pl = pinkL.process(rng.nextGaussian())
                val pr = pinkR.process(rng.nextGaussian())
                val fg = foamG.next()
                out[i] = deepL.process(pl) * deepGL.next() + washL.process(pl) * washGL.next() +
                    foamL.process(rng.nextSigned()) * fg
                out[i + 1] = deepR.process(pr) * deepGR.next() + washR.process(pr) * washGR.next() +
                    foamR.process(rng.nextSigned()) * fg
                i += 2
            }
            pos += n
        }
    }

    private fun updateControls(n: Int, ctx: RenderContext) {
        val inten = intensity.process(ctx.intensity)
        val idle = 0.5f + 0.5f * idleWave.nextBlock(n, sr)
        val target = if (ctx.running) ctx.breathFullness.coerceIn(0f, 1f) else idle
        val s = swell.process(target)
        val bal = 0.10f * balance.nextBlock(n, sr)
        val und = 1f + 0.15f * deepUnd.nextBlock(n, sr)

        val deep = OCEAN_DEEP_GAIN * und * (0.85f + 0.15f * s)
        deepGL.target(deep * (1f + bal), n)
        deepGR.target(deep * (1f - bal), n)

        val wash = OCEAN_WASH_GAIN * (0.12f + 0.88f * s * s)
        washGL.target(wash * (1f + bal), n)
        washGR.target(wash * (1f - bal), n)
        // the cutoff rises exponentially with the swell: ~350 Hz empty, ~2.4 kHz full lungs
        val cutoff = 350f * exp(1.93f * s) * (1f + 0.2f * inten)
        if (abs(cutoff - washCutoff) > washCutoff * 0.01f) programWash(cutoff)

        val crest = smoothstep(0.55f, 1f, s)
        foamG.target(OCEAN_FOAM_GAIN * crest * crest * crest * (1f + 0.5f * inten), n)
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
