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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Rain on leaves, in three layers:
 *
 *  (a) A steady bed: pink noise per channel, band-shaped to roughly 1.5–7 kHz (bandpass, then a
 *      gentle high-pass to lift the mud and a low-pass to keep the top polite).  Its level and
 *      centre undulate on three incommensurate LFOs (0.05–0.11 Hz) so it never sits still.
 *  (b) Droplets: a Poisson stream of ~60–140 short pings per second.  Each ping is a two-pole
 *      resonator (coupled-form oscillator with decay) kicked by a 0.6–3 ms triangular-windowed
 *      noise burst – a bandpass-filtered noise burst that rings out as a damped sine – at a
 *      log-uniform random pitch in 1.2–6 kHz, random amplitude and pan.  One in five is a
 *      "plink" (a drop on water): longer ring, rising pitch.  A pool of 24 voices is recycled
 *      round-robin; at the peak rate a voice is reused after ~170 ms, long after it has died.
 *  (c) Gusts: every minute or so the rain swells for 8–13 s on a raised-cosine envelope, which
 *      raises the drop rate, lifts the bed and darkens it a shade.
 *
 * Everything is driven by one "density" control computed once per RAIN_BLOCK samples; the host
 * buffer is chopped into such sub-blocks so behaviour does not depend on its size.  Density rises
 * with session intensity by up to +10 %.
 */
private const val RAIN_BLOCK = 256
private const val RAIN_VOICES = 24
private const val RAIN_TWO_PI = 6.2831855f

/** Bed shaping. */
private const val RAIN_BP_CENTRE_HZ = 3300f
private const val RAIN_BP_Q = 0.7f
private const val RAIN_HP_HZ = 1400f
private const val RAIN_LP_HZ = 7000f
/** Bed gain at density 1 (≈ -21 dBFS RMS once the pink/bandpass chain has eaten most of the power). */
private const val RAIN_BED_GAIN = 0.62f

/** Drop events per second at density 1 (density spans ~0.56–1.6). */
private const val RAIN_DROP_RATE = 100f
/** Nominal ring amplitude of the loudest drops. */
private const val RAIN_DROP_AMP = 0.17f
/** Samples of linear release that take a finished ring to exactly zero. */
private const val RAIN_RELEASE = 32
private const val RAIN_RELEASE_INV = 1f / RAIN_RELEASE

class RainGenerator : SoundGenerator {
    override val id: SoundId get() = SoundId.RAIN

    private val rng = FastRandom(0x5A17C3E9B2D48F61L)
    private var sr = 0
    private val pan = FloatArray(2)

    // --- bed ---
    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val bpL = Biquad()
    private val bpR = Biquad()
    private val hpL = Biquad()
    private val hpR = Biquad()
    private val lpL = Biquad()
    private val lpR = Biquad()
    /** Centre the bandpasses are programmed with; re-programmed only for moves > 1 %. */
    private var bpCentre = 0f
    private val bedL = Ramp()
    private val bedR = Ramp()
    private val lfoA = Lfo(0.053f, 0.13f)
    private val lfoB = Lfo(0.081f, 0.61f)
    private val lfoC = Lfo(0.107f, 0.37f)
    private val intensity = OnePole(0.02f)

    // --- gust ---
    /** Seconds of calm left before the next gust (only counts down while idle). */
    private var gustWait = 30f
    /** 0..1 progress through the current gust; >= 1 means idle. */
    private var gustPhase = 1f
    private var gustDur = 10f
    private var gustStrength = 0f

    // --- drops ---
    private val drops = Array(RAIN_VOICES) { Drop() }
    private var nextVoice = 0
    /** Fractional samples until the next drop event. */
    private var dropCountdown = 0f

    override fun reset(sampleRate: Int) {
        sr = sampleRate
        bpL.reset(); bpR.reset(); hpL.reset(); hpR.reset(); lpL.reset(); lpR.reset()
        bpCentre = 0f
        programBed(RAIN_BP_CENTRE_HZ)
        hpL.highpass(RAIN_HP_HZ, 0.55f, sr)
        hpR.highpass(RAIN_HP_HZ, 0.55f, sr)
        lpL.lowpass(RAIN_LP_HZ, 0.6f, sr)
        lpR.lowpass(RAIN_LP_HZ, 0.6f, sr)
        bedL.set(0f)
        bedR.set(0f)
        intensity.set(0f)
        for (d in drops) d.active = false
        nextVoice = 0
        dropCountdown = 0f
        gustPhase = 1f
        gustWait = 20f + 40f * rng.nextFloat()
    }

    /** Left sits a little below, right a little above the centre – a subtly different pair of bands. */
    private fun programBed(centre: Float) {
        bpCentre = centre
        bpL.bandpass(centre * 0.96f, RAIN_BP_Q, sr)
        bpR.bandpass(centre * 1.04f, RAIN_BP_Q, sr)
    }

    override fun render(out: FloatArray, frames: Int, ctx: RenderContext) {
        if (sr == 0) reset(ctx.sampleRate)
        var pos = 0
        while (pos < frames) {
            val n = minOf(RAIN_BLOCK, frames - pos)
            val density = updateControls(n, ctx)
            renderBed(out, pos, n)
            scheduleDrops(n, density)
            for (d in drops) if (d.active) d.run(out, pos, n, rng)
            pos += n
        }
    }

    /** Block-rate control: LFOs, gust envelope and intensity folded into one density value (~0.56–1.6). */
    private fun updateControls(n: Int, ctx: RenderContext): Float {
        val dt = n.toFloat() / sr
        val a = lfoA.nextBlock(n, sr)
        val b = lfoB.nextBlock(n, sr)
        val c = lfoC.nextBlock(n, sr)
        val gust = advanceGust(dt)
        val density = (0.78f + 0.14f * a + 0.08f * b + gust) * (1f + 0.10f * intensity.process(ctx.intensity))
        // Heavier rain is louder; each side also gets a little private undulation so the image
        // never feels locked to a single fader.
        val bed = RAIN_BED_GAIN * (0.55f + 0.45f * density)
        bedL.target(bed * (1f + 0.06f * b), n)
        bedR.target(bed * (1f + 0.06f * c), n)
        // ...and a shade darker: centre slides from ~3.4 kHz (light) to ~2.9 kHz (heavy).
        val centre = RAIN_BP_CENTRE_HZ * (1.12f - 0.15f * density.coerceIn(0.5f, 1.6f))
        if (abs(centre - bpCentre) > RAIN_BP_CENTRE_HZ * 0.01f) programBed(centre)
        return density
    }

    /** Advances the gust state by [dt] seconds and returns its contribution to density (0 when idle). */
    private fun advanceGust(dt: Float): Float {
        if (gustPhase < 1f) {
            gustPhase += dt / gustDur
            if (gustPhase >= 1f) {
                gustPhase = 1f
                return 0f
            }
            // Raised cosine: zero slope at both ends, peak in the middle.
            return gustStrength * 0.5f * (1f - cos(RAIN_TWO_PI * gustPhase))
        }
        gustWait -= dt
        if (gustWait <= 0f) {
            gustPhase = 0f
            gustDur = 8f + 5f * rng.nextFloat()
            gustStrength = 0.25f + 0.35f * rng.nextFloat()
            // Poisson gap before the next one (mean 60 s), with a 20 s floor.
            gustWait = 20f + 40f * expo()
        }
        return 0f
    }

    /** Unit-mean exponential deviate. */
    private fun expo(): Float = -ln(1f - rng.nextFloat())

    private fun renderBed(out: FloatArray, pos: Int, n: Int) {
        var i = pos * 2
        val end = (pos + n) * 2
        while (i < end) {
            val l = lpL.process(hpL.process(bpL.process(pinkL.process(rng.nextGaussian()))))
            val r = lpR.process(hpR.process(bpR.process(pinkR.process(rng.nextGaussian()))))
            out[i] = l * bedL.next()
            out[i + 1] = r * bedR.next()
            i += 2
        }
    }

    /** Poisson timing: walks the countdown through this block, triggering a voice at each event. */
    private fun scheduleDrops(n: Int, density: Float) {
        val perSample = RAIN_DROP_RATE * density / sr
        var t = dropCountdown
        while (t < n) {
            trigger(t.toInt())
            t += (expo() / perSample).coerceAtLeast(1f)
        }
        dropCountdown = t - n
    }

    private fun trigger(start: Int) {
        val d = drops[nextVoice]
        if (++nextVoice == RAIN_VOICES) nextVoice = 0
        val plink = rng.nextFloat() < 0.2f
        val u = rng.nextFloat()
        // Log-uniform pitch: ticks 1.2–6 kHz, plinks 1.2–3.6 kHz (ln 5 and ln 3).
        val hz = 1200f * exp(u * (if (plink) 1.0986f else 1.6094f))
        // Bigger (lower) drops ring longer: ticks 2.5–9 ms, plinks 8–22 ms.
        val tau = if (plink) 0.008f + 0.014f * rng.nextFloat() else 0.0025f + 0.0065f * (1f - u)
        val w = RAIN_TWO_PI * hz / sr
        val a = rng.nextFloat()
        val amp = RAIN_DROP_AMP * (0.25f + 0.75f * a * a) * (if (plink) 0.7f else 1f)
        val ring = (7f * tau * sr).toInt()          // ring to ≈ -60 dB, then the linear release
        d.start = start
        d.re = 0f
        d.im = 0f
        d.c = cos(w)
        d.s = sin(w)
        d.r = exp(-1f / (tau * sr))
        d.ring = ring
        d.rel = RAIN_RELEASE
        if (plink) {
            // Pitch rises 25–45 % over the ring: the bubble resonance of a drop hitting water.
            val dw = w * (0.25f + 0.2f * rng.nextFloat()) / ring.coerceAtLeast(1)
            d.dc = cos(dw)
            d.ds = sin(dw)
        } else {
            d.dc = 1f
            d.ds = 0f
        }
        // Excitation burst 0.6–3.1 ms.  With a triangular window and uniform noise the resonator
        // ends the burst at |z| ≈ excGain·sqrt(len)/3, so this scale makes the ring peak ≈ amp
        // (Rayleigh-spread, which is welcome variety).
        val exc = (sr * (0.0006f + 0.0025f * rng.nextFloat())).toInt().coerceAtLeast(4)
        d.excLen = exc
        d.excHalf = exc / 2
        d.excPos = 0
        d.excUp = 1f / d.excHalf
        d.excDown = 1f / (exc - d.excHalf)
        d.excGain = 3f * amp / sqrt(exc.toFloat())
        panGains(rng.nextSigned(), pan)
        d.gl = pan[0]
        d.gr = pan[1]
        d.active = true
    }

    /**
     * One droplet voice: a decaying rotation (re, im) at angle (c, s) per sample, with the angle
     * itself rotated by (dc, ds) per sample for the plink chirp.  Output is `im`, which starts at
     * zero; the excitation window starts at zero; the release ends at zero.
     */
    private class Drop {
        var active = false
        /** Sample offset inside the block at which this voice begins (non-zero only on its first block). */
        var start = 0
        var re = 0f
        var im = 0f
        var c = 1f
        var s = 0f
        var dc = 1f
        var ds = 0f
        var r = 0f
        var excPos = 0
        var excLen = 0
        var excHalf = 0
        var excUp = 0f
        var excDown = 0f
        var excGain = 0f
        var ring = 0
        var rel = 0
        var gl = 0f
        var gr = 0f

        /** Adds this voice into the interleaved block that starts at frame [pos] and spans [n] frames. */
        fun run(out: FloatArray, pos: Int, n: Int, rng: FastRandom) {
            var i = start
            start = 0
            var re = this.re
            var im = this.im
            var c = this.c
            var s = this.s
            val dc = this.dc
            val ds = this.ds
            val r = this.r
            val gl = this.gl
            val gr = this.gr
            var idx = (pos + i) * 2
            // 1. Excitation: triangular-windowed noise driving the resonator.
            while (i < n && excPos < excLen) {
                val env = if (excPos < excHalf) excPos * excUp else (excLen - excPos) * excDown
                val x = rng.nextSigned() * env * excGain
                val re2 = (re * c - im * s) * r
                im = (re * s + im * c) * r + x
                re = re2
                val c2 = c * dc - s * ds
                s = s * dc + c * ds
                c = c2
                out[idx] += im * gl
                out[idx + 1] += im * gr
                excPos++
                i++
                idx += 2
            }
            // 2. Free ring.
            while (i < n && ring > 0) {
                val re2 = (re * c - im * s) * r
                im = (re * s + im * c) * r
                re = re2
                val c2 = c * dc - s * ds
                s = s * dc + c * ds
                c = c2
                out[idx] += im * gl
                out[idx + 1] += im * gr
                ring--
                i++
                idx += 2
            }
            // 3. Linear release of the (already ~-60 dB) tail to exactly zero.
            while (i < n && rel > 0) {
                val re2 = (re * c - im * s) * r
                im = (re * s + im * c) * r
                re = re2
                val c2 = c * dc - s * ds
                s = s * dc + c * ds
                c = c2
                val y = im * (rel * RAIN_RELEASE_INV)
                out[idx] += y * gl
                out[idx + 1] += y * gr
                rel--
                i++
                idx += 2
            }
            this.re = re
            this.im = im
            this.c = c
            this.s = s
            if (rel == 0) active = false
        }
    }
}
