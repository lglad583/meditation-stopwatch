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
import com.meditation.stopwatch.audio.panGains
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/*
 * A campfire, in three layers:
 *
 *  (a) The ember roar: white noise through two cascaded lowpasses (a 90 Hz one-pole and a 260 Hz
 *      biquad) – a deep, brown rumble – whose level flickers on a random walk retargeted every
 *      60–180 ms and smoothed at ~3 Hz.  That flicker is the flame licking, far faster than the
 *      breath but too soft and too irregular to read as a pulse.
 *  (b) The hiss: pink noise through a 2.6 kHz bandpass at a low level, following the same flicker.
 *  (c) Crackles: a Poisson stream of ~9 events per second.  Most are short noise bursts (2–10 ms
 *      exponential decay) through a 1.8 kHz highpass at heavy-tailed random amplitudes; one in six
 *      is a "pop" – a damped sine ping at 500–1500 Hz ringing 15–40 ms on top of the burst.  Every
 *      few seconds a "flare" multiplies the rate by 2–4 for 0.4–1.2 s, so the crackle comes in
 *      clusters the way a settling log does.  A pool of 16 voices; a busy pool skips the event.
 *
 * Intensity raises the crackle rate by up to 20 % and deepens the flicker by 10 %.
 */
private const val FIRE_BLOCK = 256
private const val FIRE_VOICES = 16
private const val FIRE_TWO_PI = 6.2831855f
/** White → 90 Hz one-pole → 260 Hz lowpass has RMS ≈ 0.05; this sits near -26 dBFS. */
private const val FIRE_EMBER_GAIN = 1.0f
private const val FIRE_HISS_GAIN = 0.05f
private const val FIRE_CRACKLE_RATE = 9f
private const val FIRE_CRACKLE_AMP = 0.28f

class CampfireGenerator : SoundGenerator {
    override val id: SoundId get() = SoundId.CAMPFIRE

    private val rng = FastRandom(0x4F1E5C0A9B3D7E62L xor System.nanoTime())
    private var sr = 0
    private val pan = FloatArray(2)

    // --- ember + hiss ---
    private val emberPoleL = OnePole()
    private val emberPoleR = OnePole()
    private val emberL = Biquad()
    private val emberR = Biquad()
    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val hissL = Biquad()
    private val hissR = Biquad()
    private val emberGL = Ramp()
    private val emberGR = Ramp()
    private val hissG = Ramp()
    private val flicker = OnePole()
    private var flickerTarget = 0.5f
    private var flickerWait = 0
    private val slow = Lfo(0.019f, 0.4f)
    private val balance = Lfo(0.027f, 0.8f)
    private val intensity = OnePole(0.02f)

    // --- crackles ---
    private val voices = Array(FIRE_VOICES) { Crackle() }
    private var crackleCountdown = 0f
    private var flareLeft = 0f
    private var flareWait = 4f
    private var flareMul = 1f

    override fun reset(sampleRate: Int) {
        sr = sampleRate
        emberPoleL.set(0f); emberPoleR.set(0f)
        emberPoleL.setCutoff(90f, sr)
        emberPoleR.setCutoff(95f, sr)
        emberL.reset(); emberR.reset(); hissL.reset(); hissR.reset()
        emberL.lowpass(260f, 0.7f, sr)
        emberR.lowpass(250f, 0.7f, sr)
        hissL.bandpass(2600f, 0.8f, sr)
        hissR.bandpass(2900f, 0.8f, sr)
        emberGL.set(0f); emberGR.set(0f); hissG.set(0f)
        flicker.set(0.5f)
        flicker.setCutoff(3f, sr / FIRE_BLOCK)
        flickerWait = 0
        intensity.set(0f)
        for (v in voices) { v.active = false; v.hp.reset() }
        crackleCountdown = 0f
        flareLeft = 0f
        flareWait = 2f + 4f * rng.nextFloat()
        flareMul = 1f
    }

    override fun render(out: FloatArray, frames: Int, ctx: RenderContext) {
        if (sr == 0) reset(ctx.sampleRate)
        var pos = 0
        while (pos < frames) {
            val n = minOf(FIRE_BLOCK, frames - pos)
            val rate = updateControls(n, ctx)
            renderBed(out, pos, n)
            scheduleCrackles(n, rate)
            for (v in voices) if (v.active) v.run(out, pos, n, rng)
            pos += n
        }
    }

    /** Block-rate control; returns the crackle rate multiplier. */
    private fun updateControls(n: Int, ctx: RenderContext): Float {
        val inten = intensity.process(ctx.intensity)
        val dt = n.toFloat() / sr
        flickerWait -= n
        if (flickerWait <= 0) {
            flickerTarget = rng.nextFloat()
            flickerWait = (sr * (0.06f + 0.12f * rng.nextFloat())).toInt()
        }
        val f = flicker.process(flickerTarget)
        val depth = 0.30f + 0.10f * inten
        val lick = (1f - depth + 2f * depth * f) * (1f + 0.12f * slow.nextBlock(n, sr))
        val bal = 0.08f * balance.nextBlock(n, sr)
        val ember = FIRE_EMBER_GAIN * lick
        emberGL.target(ember * (1f + bal), n)
        emberGR.target(ember * (1f - bal), n)
        hissG.target(FIRE_HISS_GAIN * lick * lick, n)

        // flares: bursts of crackle every few seconds
        if (flareLeft > 0f) {
            flareLeft -= dt
            if (flareLeft <= 0f) flareMul = 1f
        } else {
            flareWait -= dt
            if (flareWait <= 0f) {
                flareLeft = 0.4f + 0.8f * rng.nextFloat()
                flareMul = 2f + 2f * rng.nextFloat()
                flareWait = 2f + 6f * expo()
            }
        }
        return flareMul * (1f + 0.2f * inten)
    }

    private fun renderBed(out: FloatArray, pos: Int, n: Int) {
        var i = pos * 2
        val end = (pos + n) * 2
        while (i < end) {
            val hg = hissG.next()
            out[i] = emberL.process(emberPoleL.process(rng.nextGaussian())) * emberGL.next() +
                hissL.process(pinkL.process(rng.nextGaussian())) * hg
            out[i + 1] = emberR.process(emberPoleR.process(rng.nextGaussian())) * emberGR.next() +
                hissR.process(pinkR.process(rng.nextGaussian())) * hg
            i += 2
        }
    }

    private fun scheduleCrackles(n: Int, rate: Float) {
        val perSample = FIRE_CRACKLE_RATE * rate / sr
        var t = crackleCountdown
        while (t < n) {
            trigger(t.toInt())
            t += (expo() / perSample).coerceAtLeast(1f)
        }
        crackleCountdown = t - n
    }

    private fun expo(): Float = -ln(1f - rng.nextFloat())

    private fun trigger(start: Int) {
        var v: Crackle? = null
        for (c in voices) if (!c.active) { v = c; break }
        if (v == null) return
        val pop = rng.nextFloat() < 0.16f
        val a = rng.nextFloat()
        val amp = FIRE_CRACKLE_AMP * (0.08f + 0.92f * a * a * a)
        val tau = (0.002f + 0.008f * rng.nextFloat()) * sr
        v.start = start
        v.burstDecay = exp(-1f / tau)
        v.burstEnv = amp
        v.left = (tau * 7f).toInt().coerceAtLeast(16)
        v.hp.reset()
        v.hp.highpass(1500f + 1200f * rng.nextFloat(), 0.7f, sr)
        if (pop) {
            val hz = 500f + 1000f * rng.nextFloat()
            val w = FIRE_TWO_PI * hz / sr
            val ptau = (0.015f + 0.025f * rng.nextFloat()) * sr
            v.c = cos(w); v.s = sin(w)
            v.r = exp(-1f / ptau)
            v.re = 0f
            v.im = 0f
            v.kick = amp * 0.6f
            v.left = maxOf(v.left, (ptau * 7f).toInt())
        } else {
            v.kick = 0f
            v.r = 0f
        }
        panGains(0.7f * rng.nextSigned(), pan)
        v.gl = pan[0]
        v.gr = pan[1]
        v.active = true
    }

    /**
     * One crackle: an exponentially decaying noise burst through a highpass, plus (for pops) a
     * damped rotating phasor kicked once at the start.  Ends with a short linear release to zero.
     */
    private class Crackle {
        var active = false
        var start = 0
        var burstEnv = 0f
        var burstDecay = 0f
        var left = 0
        val hp = Biquad()
        var c = 1f; var s = 0f; var r = 0f
        var re = 0f; var im = 0f
        var kick = 0f
        var gl = 0f; var gr = 0f

        fun run(out: FloatArray, pos: Int, n: Int, rng: FastRandom) {
            var i = start
            start = 0
            var idx = (pos + i) * 2
            if (kick != 0f) { im = kick; kick = 0f }
            while (i < n && left > 0) {
                var y = hp.process(rng.nextSigned() * burstEnv)
                burstEnv *= burstDecay
                if (r != 0f) {
                    val re2 = (re * c - im * s) * r
                    im = (re * s + im * c) * r
                    re = re2
                    y += im
                }
                // the last 32 samples ramp linearly to zero so a truncated tail cannot click
                if (left < 32) y *= left * (1f / 32f)
                out[idx] += y * gl
                out[idx + 1] += y * gr
                left--
                i++
                idx += 2
            }
            if (left <= 0) active = false
        }
    }
}
