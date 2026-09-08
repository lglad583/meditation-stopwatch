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
import kotlin.math.abs
import kotlin.math.ln

/*
 * A brook over stones, in three layers:
 *
 *  (a) The rush: pink noise per channel through a broad bandpass (~1.1 kHz, Q 0.5) and a 6 kHz
 *      lowpass.  Its level is modulated by a "burble" – a smoothed random walk refreshed every
 *      STREAM_BLOCK samples and lowpassed at ~2.5 Hz – so the water surges and eases the way a
 *      real stream does, at a rate faster than the breath but far too slow to read as a pulse.
 *  (b) Gurgles: three resonant bandpasses per side (Q 6) at 250–900 Hz fed with the same pink
 *      noise.  Their centres wander on independent random walks (retargeted every ~0.4 s and
 *      smoothed), so the gurgle pitch bends continuously; coefficients are re-programmed only for
 *      moves > 1 %.
 *  (c) Bubbles: a Poisson stream of ~8 per second, each a short sine blip (25–70 ms) whose pitch
 *      rises 30–60 % over its life (the resonance of an air bubble shrinking as it rises), with a
 *      smoothstep envelope that starts and ends at zero.  A pool of 10 voices; a fully busy pool
 *      just skips the event.
 *
 * Session intensity raises the bubble rate by up to 15 % and the burble depth by 10 %.
 */
private const val STREAM_BLOCK = 256
private const val STREAM_BUBBLES = 10
private const val STREAM_TWO_PI = 6.2831855f
private const val STREAM_PI = 3.1415927f
private const val STREAM_HALF_PI = 1.5707964f

/** Pink → 1.1 kHz bandpass (Q 0.5) → 6 kHz lowpass has RMS ≈ 0.19; this lands near -23 dBFS. */
private const val STREAM_RUSH_GAIN = 0.36f
private const val STREAM_GURGLE_GAIN = 0.11f
private const val STREAM_BUBBLE_RATE = 8f
private const val STREAM_BUBBLE_AMP = 0.06f

class StreamGenerator : SoundGenerator {
    override val id: SoundId get() = SoundId.STREAM

    private val rng = FastRandom(0x73A9D1E4C2F6B085L xor System.nanoTime())
    private var sr = 0
    private val pan = FloatArray(2)

    // --- rush ---
    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val bpL = Biquad()
    private val bpR = Biquad()
    private val lpL = Biquad()
    private val lpR = Biquad()
    private val rushL = Ramp()
    private val rushR = Ramp()
    private val burble = OnePole()
    private var burbleTarget = 0.5f
    private var burbleWait = 0
    private val tilt = Lfo(0.031f, 0.2f)
    private val intensity = OnePole(0.02f)

    // --- gurgles ---
    private val gurgle = Array(6) { Biquad() }
    private val gurgleHz = FloatArray(6)
    private val gurgleProgrammed = FloatArray(6)
    private val gurgleTarget = FloatArray(6)
    private val gurgleSmooth = Array(6) { OnePole() }
    private var gurgleWait = 0
    private val gurgleGain = Ramp()

    // --- bubbles ---
    private val bubbles = Array(STREAM_BUBBLES) { Bubble() }
    private var bubbleCountdown = 0f

    override fun reset(sampleRate: Int) {
        sr = sampleRate
        bpL.reset(); bpR.reset(); lpL.reset(); lpR.reset()
        bpL.bandpass(1050f, 0.5f, sr)
        bpR.bandpass(1150f, 0.5f, sr)
        lpL.lowpass(6000f, 0.6f, sr)
        lpR.lowpass(6000f, 0.6f, sr)
        rushL.set(0f); rushR.set(0f)
        burble.set(0.5f)
        burble.setCutoff(2.5f, sr / STREAM_BLOCK)
        burbleWait = 0
        intensity.set(0f)
        for (k in 0 until 6) {
            gurgle[k].reset()
            val hz = 250f + 650f * ((k % 3) / 2f) * (0.8f + 0.4f * rng.nextFloat())
            gurgleHz[k] = hz
            gurgleTarget[k] = hz
            gurgleProgrammed[k] = 0f
            gurgleSmooth[k].set(hz)
            gurgleSmooth[k].setCutoff(1.2f, sr / STREAM_BLOCK)
            program(k, hz)
        }
        gurgleWait = 0
        gurgleGain.set(0f)
        for (b in bubbles) b.active = false
        bubbleCountdown = 0f
    }

    private fun program(k: Int, hz: Float) {
        gurgleProgrammed[k] = hz
        gurgle[k].bandpass(hz, 6f, sr)
    }

    override fun render(out: FloatArray, frames: Int, ctx: RenderContext) {
        if (sr == 0) reset(ctx.sampleRate)
        var pos = 0
        while (pos < frames) {
            val n = minOf(STREAM_BLOCK, frames - pos)
            val flow = updateControls(n, ctx)
            renderWater(out, pos, n)
            scheduleBubbles(n, flow)
            for (b in bubbles) if (b.active) b.run(out, pos, n)
            pos += n
        }
    }

    /** Block-rate control; returns the flow level (~0.6–1.4) that drives bubble density. */
    private fun updateControls(n: Int, ctx: RenderContext): Float {
        val inten = intensity.process(ctx.intensity)
        // burble: random walk retargeted every 80–200 ms, smoothed at ~2.5 Hz
        burbleWait -= n
        if (burbleWait <= 0) {
            burbleTarget = rng.nextFloat()
            burbleWait = (sr * (0.08f + 0.12f * rng.nextFloat())).toInt()
        }
        val b = burble.process(burbleTarget)
        val depth = 0.35f + 0.10f * inten
        val flow = 1f - depth + 2f * depth * b
        val tl = tilt.nextBlock(n, sr)
        val g = STREAM_RUSH_GAIN * (0.7f + 0.3f * flow)
        rushL.target(g * (1f + 0.08f * tl), n)
        rushR.target(g * (1f - 0.08f * tl), n)

        // gurgle centres: retarget every ~0.4 s, glide continuously
        gurgleWait -= n
        if (gurgleWait <= 0) {
            for (k in 0 until 6) {
                val base = 250f + 650f * ((k % 3) / 2f)
                gurgleTarget[k] = base * (0.75f + 0.5f * rng.nextFloat())
            }
            gurgleWait = (sr * (0.3f + 0.3f * rng.nextFloat())).toInt()
        }
        for (k in 0 until 6) {
            val hz = gurgleSmooth[k].process(gurgleTarget[k])
            gurgleHz[k] = hz
            if (abs(hz - gurgleProgrammed[k]) > gurgleProgrammed[k] * 0.01f) program(k, hz)
        }
        gurgleGain.target(STREAM_GURGLE_GAIN * (0.5f + 0.5f * flow), n)
        return flow * (1f + 0.15f * inten)
    }

    private fun renderWater(out: FloatArray, pos: Int, n: Int) {
        var i = pos * 2
        val end = (pos + n) * 2
        while (i < end) {
            val pl = pinkL.process(rng.nextGaussian())
            val pr = pinkR.process(rng.nextGaussian())
            val gg = gurgleGain.next()
            val gl = gurgle[0].process(pl) + gurgle[1].process(pl) + gurgle[2].process(pl)
            val gr = gurgle[3].process(pr) + gurgle[4].process(pr) + gurgle[5].process(pr)
            out[i] = lpL.process(bpL.process(pl)) * rushL.next() + gl * gg
            out[i + 1] = lpR.process(bpR.process(pr)) * rushR.next() + gr * gg
            i += 2
        }
    }

    private fun scheduleBubbles(n: Int, flow: Float) {
        val perSample = STREAM_BUBBLE_RATE * flow / sr
        var t = bubbleCountdown
        while (t < n) {
            trigger(t.toInt())
            t += (expo() / perSample).coerceAtLeast(1f)
        }
        bubbleCountdown = t - n
    }

    private fun expo(): Float = -ln(1f - rng.nextFloat())

    private fun trigger(start: Int) {
        var b: Bubble? = null
        for (c in bubbles) if (!c.active) { b = c; break }
        if (b == null) return
        val len = (sr * (0.025f + 0.045f * rng.nextFloat())).toInt().coerceAtLeast(64)
        val hz = 350f + 600f * rng.nextFloat()
        val a = rng.nextFloat()
        b.start = start
        b.pos = 0
        b.len = len
        b.invLen = 1f / len
        b.ph = 0f
        b.inc0 = STREAM_TWO_PI * hz / sr
        b.rise = 0.3f + 0.3f * rng.nextFloat()
        b.amp = STREAM_BUBBLE_AMP * (0.3f + 0.7f * a * a)
        panGains(0.8f * rng.nextSigned(), pan)
        b.gl = pan[0]
        b.gr = pan[1]
        b.active = true
    }

    /** One bubble: a sine blip with rising pitch and a smoothstep envelope (zero at both ends). */
    private class Bubble {
        var active = false
        var start = 0
        var pos = 0
        var len = 1
        var invLen = 1f
        var ph = 0f
        var inc0 = 0f
        var rise = 0f
        var amp = 0f
        var gl = 0f
        var gr = 0f

        fun run(out: FloatArray, pos0: Int, n: Int) {
            var i = start
            start = 0
            var idx = (pos0 + i) * 2
            while (i < n) {
                val t = pos * invLen
                // envelope: fast smoothstep attack over the first 20 %, smoothstep decay after
                val e = if (t < 0.2f) { val x = t * 5f; x * x * (3f - 2f * x) }
                        else { val x = (1f - t) * 1.25f; x * x * (3f - 2f * x) }
                var p = ph + inc0 * (1f + rise * t)
                if (p >= STREAM_PI) p -= STREAM_TWO_PI
                ph = p
                val y = fastSin(p) * e * amp
                out[idx] += y * gl
                out[idx + 1] += y * gr
                pos++
                i++
                idx += 2
                if (pos >= len) { active = false; return }
            }
        }

        /** Odd 9th-order polynomial sine for x in [-PI, PI). */
        private fun fastSin(x: Float): Float {
            var t = x
            if (t > STREAM_HALF_PI) t = STREAM_PI - t else if (t < -STREAM_HALF_PI) t = -STREAM_PI - t
            val t2 = t * t
            return t * (1f + t2 * (-0.16666667f + t2 * (0.008333333f + t2 * (-1.9841270e-4f + t2 * 2.7557319e-6f))))
        }
    }
}
