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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * A dawn meadow, in four layers (all written into the same stereo buffer):
 *
 *  1. Wind/leaves bed – two independent pink-noise channels through a slowly undulating lowpass
 *     (750 ± 350 Hz) with a gently wandering L/R balance, plus a faint bandpassed "leaves" hiss that
 *     rises only on gusts.  The bed follows the breath by a barely perceptible ±6 %.
 *  2. Crickets – three insects at different pans.  Each is a pulse train (14–24 Hz) of short
 *     exponentially decaying sine pings (3.8–5.2 kHz), grouped into 1–3 s chirping phrases with
 *     1–4 s pauses.  Phrase edges are gated by a 40 ms one-pole and the pings pass a 5.5 kHz
 *     one-pole so nothing above ~8 kHz survives; they are kept soft on purpose.
 *  3. Birds – six species with distinct parameter sets (start frequency, sweep shape, chirp length,
 *     chirps per phrase, gaps, vibrato).  Phrases start with Poisson timing every ~2–7 s (15 % more
 *     often at full intensity), each from a random species, at a random pan and distance; distance
 *     lowers the level and lowpasses the voice (12 dB/oct, 3–8 kHz).  Chirps are a sine plus a soft
 *     second harmonic with a smoothstep attack/decay envelope that starts and ends at zero.  A pool
 *     of six voices is recycled; if all are busy the phrase is skipped.
 *  4. A distant two-note "coo" (owl/dove, 350–500 Hz) every 30–90 s.
 *
 * Everything runs on the same [FastRandom]; nothing is allocated after [reset].
 */
class NatureGenerator : SoundGenerator {
    override val id: SoundId = SoundId.NATURE

    /**
     * One kind of bird.  Chirp frequency is f(t) = fStart + fDelta * t * (sweepA + sweepB * t) with
     * t in 0..1, fDelta = fStart * (sweepRatio - 1).  UP/DOWN: A=1, B=0 (ratio above / below 1).
     * ARCH: A=4, B=-4, peaking at fStart * sweepRatio half-way through.
     */
    private class Species(
        val baseHz: Float,
        val sweepRatio: Float,
        val sweepA: Float,
        val sweepB: Float,
        val minLenMs: Float,
        val maxLenMs: Float,
        val minChirps: Int,
        val maxChirps: Int,
        val minGapMs: Float,
        val maxGapMs: Float,
        val vibDepth: Float,
        val vibHz: Float,
        val level: Float,
    )

    private class BirdVoice {
        var active = false
        var inChirp = false
        var species = 0
        var chirpsLeft = 0
        /** This individual's start frequency for the whole phrase (species base ± 8 %). */
        var identityHz = 0f
        var pos = 0
        var len = 0
        var invLen = 0f
        var gap = 0
        var fStart = 0f
        var fDelta = 0f
        var sweepA = 0f
        var sweepB = 0f
        var ph = 0f
        var vibPh = 0f
        var vibInc = 0f
        var vibDepth = 0f
        var amp = 0f
        var h2 = 0f
        var gl = 0f
        var gr = 0f
        val lp = Biquad()
    }

    private class Cricket {
        var gl = 0f
        var gr = 0f
        var level = 0f
        var period = 0
        var timer = 0
        var inc = 0f
        var ph = 0f
        var env = 0f
        var attackLeft = 0
        var attackStep = 0f
        var decay = 0f
        var chirping = false
        var stateLeft = 0
        var gateTarget = 0f
        val gate = OnePole()
        val lp = OnePole()
    }

    private companion object {
        const val SEED = 0x6E61747572655F31L
        const val PI_F = 3.14159265f
        const val HALF_PI = 1.57079633f
        const val TWO_PI = 6.28318531f

        const val BIRD_VOICES = 6
        const val BIRD_LEVEL = 0.16f
        const val CHIRP_ATTACK = 0.25f
        const val INV_CHIRP_ATTACK = 1f / CHIRP_ATTACK
        const val INV_CHIRP_DECAY = 1f / (1f - CHIRP_ATTACK)

        const val N_CRICKETS = 3
        const val CRICKET_LEVEL = 0.07f

        /** Pink → 750 Hz lowpass has RMS ≈ 0.15, so this puts the bed near -27 dBFS. */
        const val BED_LEVEL = 0.30f
        /** Pink → 2.4 kHz bandpass has RMS ≈ 0.074; leaves peak near -37 dBFS on a gust. */
        const val LEAF_LEVEL = 0.20f

        const val COO_LEVEL = 0.07f
        const val COO_ATTACK = 0.3f
        const val INV_COO_ATTACK = 1f / COO_ATTACK
        const val INV_COO_DECAY = 1f / (1f - COO_ATTACK)

        val SPECIES = arrayOf(
            // warbler: rising whistles
            Species(3200f, 1.35f, 1f, 0f, 60f, 110f, 3, 7, 40f, 90f, 0.010f, 40f, 1.0f),
            // robin: arched, fluty notes
            Species(2300f, 1.45f, 4f, -4f, 90f, 160f, 2, 4, 120f, 250f, 0.020f, 25f, 1.0f),
            // finch: quick falling ticks
            Species(4600f, 0.72f, 1f, 0f, 30f, 60f, 4, 7, 30f, 70f, 0.005f, 60f, 0.7f),
            // thrush: slow rising warble
            Species(1900f, 1.60f, 1f, 0f, 100f, 160f, 2, 3, 200f, 350f, 0.030f, 12f, 1.0f),
            // wren: rapid high arches
            Species(5200f, 1.18f, 4f, -4f, 40f, 80f, 5, 7, 40f, 80f, 0.008f, 50f, 0.6f),
            // sparrow: short falling chirps
            Species(3800f, 0.85f, 1f, 0f, 50f, 90f, 2, 5, 80f, 150f, 0.015f, 35f, 0.85f),
        )
    }

    private var sr = 48_000
    private var radPerHz = TWO_PI / 48_000f
    private var rng = FastRandom(SEED)
    private val pan2 = FloatArray(2)

    // Birds.
    private val voices = Array(BIRD_VOICES) { BirdVoice() }
    private var birdTimer = 0

    // Crickets.
    private val crickets = Array(N_CRICKETS) { Cricket() }
    private var cricketAttack = 96

    // Wind / leaves bed.
    private val pinkL = PinkFilter()
    private val pinkR = PinkFilter()
    private val bedLpL = Biquad()
    private val bedLpR = Biquad()
    private val leafL = Biquad()
    private val leafR = Biquad()
    private val bedGainL = Ramp(0f)
    private val bedGainR = Ramp(0f)
    private val leafGain = Ramp(0f)
    private val bedSmooth = OnePole()
    private val leafSmooth = OnePole()
    private val und1 = Lfo(0.037f, 0.10f)
    private val und2 = Lfo(0.0113f, 0.60f)
    private val balance = Lfo(0.021f, 0.30f)
    private val gust1 = Lfo(0.067f, 0.80f)
    private val gust2 = Lfo(0.019f, 0.45f)
    private val idleBreath = Lfo(0.09f, 0f)
    private var bedCutoff = 0f

    // Coo.
    private var cooTimer = 0
    private var cooActive = false
    private var cooInGap = false
    private var cooNote = 0
    private var cooPos = 0
    private var cooLen = 1
    private var cooInvLen = 1f
    private var cooGap = 0
    private var cooHz = 400f
    private var cooPh = 0f
    private var cooAmp = 0f
    private var cooGl = 0f
    private var cooGr = 0f

    override fun reset(sampleRate: Int) {
        sr = sampleRate
        radPerHz = TWO_PI / sr
        rng = FastRandom(SEED xor System.nanoTime())

        for (v in voices) {
            v.active = false
            v.inChirp = false
            v.lp.reset()
        }
        birdTimer = ((0.5f + 1.5f * rng.nextFloat()) * sr).toInt()

        cricketAttack = (0.002f * sr).toInt().coerceAtLeast(1)
        for (k in 0 until N_CRICKETS) {
            val c = crickets[k]
            panGains(-0.7f + 0.7f * k + 0.2f * rng.nextSigned(), pan2)   // left, centre, right
            c.gl = pan2[0]; c.gr = pan2[1]
            c.level = CRICKET_LEVEL * (0.6f + 0.4f * rng.nextFloat())
            c.period = (sr / (14f + 10f * rng.nextFloat())).toInt().coerceAtLeast(2)
            c.timer = 0
            c.inc = (3800f + 1400f * rng.nextFloat()) * radPerHz
            c.ph = 0f
            c.env = 0f
            c.attackLeft = 0
            c.attackStep = 0f
            c.decay = exp(-1f / ((0.005f + 0.004f * rng.nextFloat()) * sr))
            c.chirping = false
            c.stateLeft = ((0.5f + 3f * rng.nextFloat()) * sr).toInt()
            c.gateTarget = 0f
            c.gate.set(0f); c.gate.setCutoff(4f, sr)
            c.lp.set(0f); c.lp.setCutoff(5500f, sr)
        }

        bedCutoff = 0f   // forces a coefficient update on the first block
        bedLpL.reset(); bedLpR.reset()
        leafL.reset(); leafR.reset()
        leafL.bandpass(2400f, 0.8f, sr)
        leafR.bandpass(2650f, 0.8f, sr)   // slightly different centre: decorrelated leaves
        bedGainL.set(0f); bedGainR.set(0f); leafGain.set(0f)
        bedSmooth.set(0f); leafSmooth.set(0f)

        cooActive = false
        cooInGap = false
        cooTimer = ((20f + 40f * rng.nextFloat()) * sr).toInt()
    }

    override fun render(out: FloatArray, frames: Int, ctx: RenderContext) {
        renderBed(out, frames, ctx)       // writes
        renderCrickets(out, frames)       // the rest add
        renderBirds(out, frames, ctx)
        renderCoo(out, frames)
    }

    // ------------------------------------------------------------------ wind / leaves bed

    private fun renderBed(out: FloatArray, frames: Int, ctx: RenderContext) {
        val u = 0.5f * und1.nextBlock(frames, sr) + 0.5f * und2.nextBlock(frames, sr)
        // Breath coupling is deliberately faint: the meadow should not obviously "breathe".
        val breath = if (ctx.running) 1f + 0.12f * (ctx.breathFullness - 0.5f)
                     else 1f + 0.06f * idleBreath.nextBlock(frames, sr)
        val k = 1f - exp(-frames / (0.25f * sr))   // 250 ms block-rate smoothing
        bedSmooth.k = k
        leafSmooth.k = k
        val g = bedSmooth.process(BED_LEVEL * (1f + 0.4f * u) * breath)
        val bal = 0.12f * balance.nextBlock(frames, sr)
        bedGainL.target(g * (1f + bal), frames)
        bedGainR.target(g * (1f - bal), frames)
        val gust = 0.6f * gust1.nextBlock(frames, sr) + 0.4f * gust2.nextBlock(frames, sr)
        val gustPos = if (gust > 0f) gust else 0f
        leafGain.target(leafSmooth.process(LEAF_LEVEL * gustPos * gustPos * breath), frames)
        val cutoff = 750f + 350f * u
        if (abs(cutoff - bedCutoff) > 8f) {
            bedCutoff = cutoff
            bedLpL.lowpass(cutoff, 0.6f, sr)
            bedLpR.lowpass(cutoff, 0.6f, sr)
        }
        var i = 0
        for (n in 0 until frames) {
            val pl = pinkL.process(rng.nextSigned())
            val pr = pinkR.process(rng.nextSigned())
            val lg = leafGain.next()
            out[i] = bedLpL.process(pl) * bedGainL.next() + leafL.process(pl) * lg
            out[i + 1] = bedLpR.process(pr) * bedGainR.next() + leafR.process(pr) * lg
            i += 2
        }
    }

    // ------------------------------------------------------------------ crickets

    private fun renderCrickets(out: FloatArray, frames: Int) {
        val attack = cricketAttack
        for (c in crickets) {
            c.stateLeft -= frames
            if (c.stateLeft <= 0) {
                c.chirping = !c.chirping
                if (c.chirping) {
                    c.stateLeft = ((1f + 2f * rng.nextFloat()) * sr).toInt()
                    c.gateTarget = 1f
                    c.timer = 0   // first ping right away
                } else {
                    c.stateLeft = ((1f + 3f * rng.nextFloat()) * sr).toInt()
                    c.gateTarget = 0f
                }
            }
            if (!c.chirping && c.gate.y < 1e-4f) continue   // paused and faded out: skip the DSP
            val jitter = c.period / 50   // ±2 % ping-to-ping timing wobble
            var i = 0
            for (n in 0 until frames) {
                c.timer--
                if (c.timer <= 0) {
                    c.timer = c.period + (rng.nextSigned() * jitter).toInt()
                    c.attackLeft = attack
                    c.attackStep = (1f - c.env) / attack   // ramp from wherever the tail is: continuous
                }
                if (c.attackLeft > 0) {
                    c.env += c.attackStep
                    c.attackLeft--
                    if (c.attackLeft == 0) c.env = 1f
                } else {
                    c.env *= c.decay
                }
                var p = c.ph + c.inc; if (p >= PI_F) p -= TWO_PI; c.ph = p
                val s = c.lp.process(fastSin(p) * c.env) * c.gate.process(c.gateTarget) * c.level
                out[i] += s * c.gl
                out[i + 1] += s * c.gr
                i += 2
            }
        }
    }

    // ------------------------------------------------------------------ birds

    private fun renderBirds(out: FloatArray, frames: Int, ctx: RenderContext) {
        birdTimer -= frames
        if (birdTimer <= 0) {
            for (v in voices) {
                if (!v.active) { startPhrase(v); break }
            }
            val rate = 1f + 0.15f * ctx.intensity
            birdTimer = (min(2f + expo(2.3f), 7.5f) / rate * sr).toInt()
        }
        for (v in voices) {
            if (!v.active) continue
            val sp = SPECIES[v.species]
            var i = 0
            for (n in 0 until frames) {
                if (!v.inChirp) {
                    v.gap--
                    if (v.gap > 0) { i += 2; continue }
                    startChirp(v, sp)
                }
                val t = v.pos * v.invLen
                var vp = v.vibPh + v.vibInc; if (vp >= PI_F) vp -= TWO_PI; v.vibPh = vp
                val f = (v.fStart + v.fDelta * t * (v.sweepA + v.sweepB * t)) * (1f + v.vibDepth * fastSin(vp))
                var p = v.ph + f * radPerHz; if (p >= PI_F) p -= TWO_PI; v.ph = p
                var p2 = p + p; if (p2 >= PI_F) p2 -= TWO_PI else if (p2 < -PI_F) p2 += TWO_PI
                val e = if (t < CHIRP_ATTACK) {
                    val x = t * INV_CHIRP_ATTACK; x * x * (3f - 2f * x)
                } else {
                    val x = (1f - t) * INV_CHIRP_DECAY; x * x * (3f - 2f * x)
                }
                val y = v.lp.process((fastSin(p) + v.h2 * fastSin(p2)) * (e * v.amp))
                out[i] += y * v.gl
                out[i + 1] += y * v.gr
                i += 2
                v.pos++
                if (v.pos >= v.len) {
                    v.chirpsLeft--
                    if (v.chirpsLeft <= 0) { v.active = false; break }
                    v.inChirp = false
                    v.gap = ((sp.minGapMs + rng.nextFloat() * (sp.maxGapMs - sp.minGapMs)) * 0.001f * sr)
                        .toInt().coerceAtLeast(1)
                }
            }
        }
    }

    private fun startPhrase(v: BirdVoice) {
        val idx = (rng.nextFloat() * SPECIES.size).toInt().coerceAtMost(SPECIES.size - 1)
        val sp = SPECIES[idx]
        v.species = idx
        v.identityHz = sp.baseHz * (1f + 0.08f * rng.nextSigned())
        v.chirpsLeft = sp.minChirps + (rng.nextFloat() * (sp.maxChirps - sp.minChirps + 1)).toInt()
        val dist = rng.nextFloat()   // 0 = close, 1 = far
        v.amp = BIRD_LEVEL * sp.level * (1f - 0.75f * dist) * (0.8f + 0.2f * rng.nextFloat())
        v.lp.reset()
        v.lp.lowpass(8000f - 5000f * dist, 0.6f, sr)
        panGains(0.9f * rng.nextSigned(), pan2)
        v.gl = pan2[0]; v.gr = pan2[1]
        v.vibInc = sp.vibHz * radPerHz
        v.vibDepth = sp.vibDepth
        v.active = true
        startChirp(v, sp)
    }

    private fun startChirp(v: BirdVoice, sp: Species) {
        val lenMs = sp.minLenMs + rng.nextFloat() * (sp.maxLenMs - sp.minLenMs)
        v.len = (lenMs * 0.001f * sr).toInt().coerceAtLeast(64)
        v.invLen = 1f / v.len
        v.pos = 0
        v.fStart = v.identityHz * (1f + 0.03f * rng.nextSigned())
        v.fDelta = v.fStart * (sp.sweepRatio - 1f)
        v.sweepA = sp.sweepA
        v.sweepB = sp.sweepB
        // Less second harmonic for high species so nothing bright lands above ~8 kHz.
        v.h2 = 0.2f * min(1f, 2500f / v.fStart)
        v.inChirp = true
    }

    // ------------------------------------------------------------------ coo

    private fun renderCoo(out: FloatArray, frames: Int) {
        if (!cooActive) {
            cooTimer -= frames
            if (cooTimer > 0) return
            startCoo()
        }
        var i = 0
        for (n in 0 until frames) {
            if (cooInGap) {
                cooGap--
                if (cooGap > 0) { i += 2; continue }
                startCooNote(1)
            }
            val t = cooPos * cooInvLen
            val e = if (t < COO_ATTACK) {
                val x = t * INV_COO_ATTACK; x * x * (3f - 2f * x)
            } else {
                val x = (1f - t) * INV_COO_DECAY; x * x * (3f - 2f * x)
            }
            val f = cooHz * (1f - 0.05f * t)   // a dove's note droops slightly
            var p = cooPh + f * radPerHz; if (p >= PI_F) p -= TWO_PI; cooPh = p
            var p2 = p + p; if (p2 >= PI_F) p2 -= TWO_PI else if (p2 < -PI_F) p2 += TWO_PI
            val s = (fastSin(p) + 0.3f * fastSin(p2)) * (e * cooAmp)
            out[i] += s * cooGl
            out[i + 1] += s * cooGr
            i += 2
            cooPos++
            if (cooPos >= cooLen) {
                if (cooNote == 0) {
                    cooInGap = true
                    cooGap = ((0.10f + 0.06f * rng.nextFloat()) * sr).toInt().coerceAtLeast(1)
                } else {
                    cooActive = false
                    cooTimer = ((30f + 60f * rng.nextFloat()) * sr).toInt()
                    break
                }
            }
        }
    }

    private fun startCoo() {
        cooActive = true
        cooHz = 350f + 150f * rng.nextFloat()
        cooAmp = COO_LEVEL * (0.6f + 0.4f * rng.nextFloat())
        panGains(0.8f * rng.nextSigned(), pan2)
        cooGl = pan2[0]; cooGr = pan2[1]
        startCooNote(0)
    }

    private fun startCooNote(note: Int) {
        cooNote = note
        cooInGap = false
        cooPos = 0
        val baseSec = if (note == 0) 0.24f else 0.42f
        cooLen = ((baseSec + 0.06f * rng.nextFloat()) * sr).toInt().coerceAtLeast(64)
        cooInvLen = 1f / cooLen
        if (note == 1) cooHz *= 0.92f   // second note a little lower
    }

    // ------------------------------------------------------------------ helpers

    /** Exponentially distributed interval (Poisson process) with the given mean, in seconds. */
    private fun expo(mean: Float): Float = -ln(1f - rng.nextFloat()) * mean

    /** Odd 9th-order polynomial sine for x in [-PI, PI); |error| < 4e-6 (about -108 dB). */
    private fun fastSin(x: Float): Float {
        var t = x
        if (t > HALF_PI) t = PI_F - t else if (t < -HALF_PI) t = -PI_F - t
        val t2 = t * t
        return t * (1f + t2 * (-0.16666667f + t2 * (0.008333333f + t2 * (-1.9841270e-4f + t2 * 2.7557319e-6f))))
    }
}
