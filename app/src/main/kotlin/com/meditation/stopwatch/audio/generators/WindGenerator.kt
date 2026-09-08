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

/*
 * Wind through pines.
 *
 * Everything hangs off one 0..1 "gust" control: three incommensurate LFOs (periods 20, 43 and
 * 77 s) plus a random walk retargeted every 1–3 s and smoothed over ~2 s, squared so that calm
 * spells are long and gusts arrive as swells rather than as a constant roar.
 *
 *  (a) The body: pink noise per channel through a lowpass whose cutoff climbs from ~300 Hz in a
 *      lull to ~1.6 kHz in a gust, gain rising with the gust.  The rising cutoff is the whole
 *      trick – wind is heard as a brightening, not as a volume change.
 *  (b) The whistle: one narrow resonance per side (bandpass, Q 9) at 400–1300 Hz that follows the
 *      gust with its own slow wander, audible only in the upper half of a gust.  Left and right
 *      sit a semitone apart so the whistle is wide rather than a point.
 *  (c) Needles: the hiss of air through pine needles – pink noise through a 3.5 kHz bandpass,
 *      gated by the gust squared.
 *
 * Coefficients are re-programmed only for moves > 1 %.  Intensity adds up to 15 % to gust depth
 * and lets the body open a little brighter.
 */
private const val WIND_BLOCK = 256
/** Pink → ~700 Hz lowpass has RMS ≈ 0.15; a mid gust sits near -25 dBFS. */
private const val WIND_BODY_GAIN = 0.55f
private const val WIND_WHISTLE_GAIN = 0.20f
private const val WIND_NEEDLE_GAIN = 0.16f

class WindGenerator : SoundGenerator {
    override val id: SoundId get() = SoundId.WIND

    private val rng = FastRandom(0x57194DA3C6E8B0F2L xor System.nanoTime())
    private var sr = 0

    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val bodyL = Biquad()
    private val bodyR = Biquad()
    private val whistleL = Biquad()
    private val whistleR = Biquad()
    private val needleL = Biquad()
    private val needleR = Biquad()
    private var bodyCutoff = 0f
    private var whistleHz = 0f

    private val bodyGL = Ramp()
    private val bodyGR = Ramp()
    private val whistleG = Ramp()
    private val needleG = Ramp()

    private val lfoA = Lfo(0.050f, 0.15f)
    private val lfoB = Lfo(0.023f, 0.55f)
    private val lfoC = Lfo(0.013f, 0.80f)
    private val balance = Lfo(0.017f, 0.3f)
    private val whistleWander = Lfo(0.037f, 0.7f)
    private val walk = OnePole()
    private var walkTarget = 0.5f
    private var walkWait = 0
    private val intensity = OnePole(0.02f)

    override fun reset(sampleRate: Int) {
        sr = sampleRate
        bodyL.reset(); bodyR.reset(); whistleL.reset(); whistleR.reset(); needleL.reset(); needleR.reset()
        needleL.bandpass(3400f, 0.9f, sr)
        needleR.bandpass(3700f, 0.9f, sr)
        bodyCutoff = 0f
        programBody(400f)
        whistleHz = 0f
        programWhistle(500f)
        bodyGL.set(0f); bodyGR.set(0f); whistleG.set(0f); needleG.set(0f)
        walk.set(0.5f)
        walk.setCutoff(0.08f, sr / WIND_BLOCK)   // ~2 s smoothing at block rate
        walkWait = 0
        intensity.set(0f)
    }

    private fun programBody(hz: Float) {
        bodyCutoff = hz
        bodyL.lowpass(hz, 0.7f, sr)
        bodyR.lowpass(hz * 1.05f, 0.7f, sr)
    }

    private fun programWhistle(hz: Float) {
        whistleHz = hz
        whistleL.bandpass(hz, 9f, sr)
        whistleR.bandpass(hz * 1.06f, 9f, sr)
    }

    override fun render(out: FloatArray, frames: Int, ctx: RenderContext) {
        if (sr == 0) reset(ctx.sampleRate)
        var pos = 0
        while (pos < frames) {
            val n = minOf(WIND_BLOCK, frames - pos)
            updateControls(n, ctx)
            var i = pos * 2
            val end = (pos + n) * 2
            while (i < end) {
                val pl = pinkL.process(rng.nextGaussian())
                val pr = pinkR.process(rng.nextGaussian())
                val wg = whistleG.next()
                val ng = needleG.next()
                out[i] = bodyL.process(pl) * bodyGL.next() + whistleL.process(pl) * wg + needleL.process(pl) * ng
                out[i + 1] = bodyR.process(pr) * bodyGR.next() + whistleR.process(pr) * wg + needleR.process(pr) * ng
                i += 2
            }
            pos += n
        }
    }

    private fun updateControls(n: Int, ctx: RenderContext) {
        val inten = intensity.process(ctx.intensity)
        walkWait -= n
        if (walkWait <= 0) {
            walkTarget = rng.nextFloat()
            walkWait = (sr * (1f + 2f * rng.nextFloat())).toInt()
        }
        val w = walk.process(walkTarget)
        val a = lfoA.nextBlock(n, sr)
        val b = lfoB.nextBlock(n, sr)
        val c = lfoC.nextBlock(n, sr)
        // 0..1, squared: mostly calm, occasional swells
        var g = 0.5f + 0.18f * a + 0.14f * b + 0.10f * c + 0.3f * (w - 0.5f)
        g = g.coerceIn(0f, 1f)
        val gust = g * g * (1f + 0.15f * inten)
        val bal = 0.12f * balance.nextBlock(n, sr)

        val body = WIND_BODY_GAIN * (0.25f + 0.75f * gust)
        bodyGL.target(body * (1f + bal), n)
        bodyGR.target(body * (1f - bal), n)
        val cutoff = (300f + 1300f * gust) * (1f + 0.15f * inten)
        if (abs(cutoff - bodyCutoff) > bodyCutoff * 0.01f) programBody(cutoff)

        val wh = (400f + 900f * gust) * (1f + 0.08f * whistleWander.nextBlock(n, sr))
        if (abs(wh - whistleHz) > whistleHz * 0.01f) programWhistle(wh)
        val upper = ((gust - 0.4f) / 0.6f).coerceIn(0f, 1f)
        whistleG.target(WIND_WHISTLE_GAIN * upper * upper, n)

        needleG.target(WIND_NEEDLE_GAIN * gust * gust, n)
    }
}
