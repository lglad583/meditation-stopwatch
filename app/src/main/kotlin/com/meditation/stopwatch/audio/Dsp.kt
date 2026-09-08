package com.meditation.stopwatch.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Tiny allocation-free DSP toolbox shared by the generators. All classes are single-threaded. */

/** One-pole smoother / lowpass: y += k * (x - y). */
class OnePole(var k: Float = 0.01f) {
    var y = 0f
    fun process(x: Float): Float { y += k * (x - y); return y }
    fun set(x: Float) { y = x }
    /** Configure from a cutoff in Hz. */
    fun setCutoff(hz: Float, sampleRate: Int) { k = (1f - exp(-2.0 * PI * hz / sampleRate)).toFloat() }
}

/** Biquad filter (RBJ cookbook), Direct Form I, float. */
class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
    fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }

    private fun set(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        this.b0 = (b0 / a0).toFloat(); this.b1 = (b1 / a0).toFloat(); this.b2 = (b2 / a0).toFloat()
        this.a1 = (a1 / a0).toFloat(); this.a2 = (a2 / a0).toFloat()
    }
    fun lowpass(hz: Float, q: Float, sr: Int) {
        val w = 2 * PI * hz.coerceIn(10f, sr * 0.45f) / sr; val c = cos(w); val alpha = sin(w) / (2 * q)
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }
    fun highpass(hz: Float, q: Float, sr: Int) {
        val w = 2 * PI * hz.coerceIn(10f, sr * 0.45f) / sr; val c = cos(w); val alpha = sin(w) / (2 * q)
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }
    /** Constant 0 dB peak gain bandpass. */
    fun bandpass(hz: Float, q: Float, sr: Int) {
        val w = 2 * PI * hz.coerceIn(10f, sr * 0.45f) / sr; val c = cos(w); val alpha = sin(w) / (2 * q)
        set(alpha, 0.0, -alpha, 1 + alpha, -2 * c, 1 - alpha)
    }
    fun peaking(hz: Float, q: Float, gainDb: Float, sr: Int) {
        val a = Math.pow(10.0, gainDb / 40.0); val w = 2 * PI * hz.coerceIn(10f, sr * 0.45f) / sr
        val c = cos(w); val alpha = sin(w) / (2 * q)
        set(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }
}

/** DC blocker: y = x - x1 + R*y1. */
class DcBlock(private val r: Float = 0.995f) {
    private var x1 = 0f; private var y1 = 0f
    fun process(x: Float): Float { val y = x - x1 + r * y1; x1 = x; y1 = y; return y }
}

/** Pink noise (Paul Kellet's refined method). Feed white noise in, get pink out, roughly unit gain. */
class PinkFilter {
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f; private var b3 = 0f; private var b4 = 0f; private var b5 = 0f; private var b6 = 0f
    fun process(white: Float): Float {
        b0 = 0.99886f * b0 + white * 0.0555179f
        b1 = 0.99332f * b1 + white * 0.0750759f
        b2 = 0.96900f * b2 + white * 0.1538520f
        b3 = 0.86650f * b3 + white * 0.3104856f
        b4 = 0.55000f * b4 + white * 0.5329522f
        b5 = -0.7616f * b5 - white * 0.0168980f
        val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f
        b6 = white * 0.115926f
        return pink * 0.11f
    }
}

/** Slow sine LFO, phase in cycles. */
class Lfo(private var hz: Float, phase: Float = 0f) {
    private var ph = phase
    fun setRate(hz: Float) { this.hz = hz }
    /** Advance by one sample at [sr] and return -1..1. */
    fun next(sr: Int): Float { ph += hz / sr; if (ph >= 1f) ph -= 1f; return sin(2.0 * PI * ph).toFloat() }
    /** Advance by [n] samples and return -1..1 (cheap block-rate LFO). */
    fun nextBlock(n: Int, sr: Int): Float { ph += hz * n / sr; ph -= kotlin.math.floor(ph); return sin(2.0 * PI * ph).toFloat() }
}

/** Linear-ramp gain: call [target] once per block, then [next] per sample; click-free. */
class Ramp(initial: Float = 0f) {
    private var v = initial; private var step = 0f; private var remaining = 0
    val value: Float get() = v
    fun target(t: Float, samples: Int) { step = (t - v) / samples.coerceAtLeast(1); remaining = samples }
    fun next(): Float { if (remaining > 0) { v += step; remaining-- }; return v }
    fun set(x: Float) { v = x; step = 0f; remaining = 0 }
}

/** Equal-power pan: returns (left, right) gains for pan in -1..1 into [out]. */
fun panGains(pan: Float, out: FloatArray) {
    val a = (pan.coerceIn(-1f, 1f) + 1f) * 0.25f * PI.toFloat()
    out[0] = cos(a); out[1] = sin(a)
}

/** Soft clipper, transparent below ~0.5. */
fun softClip(x: Float): Float = if (x > 1.5f) 1f else if (x < -1.5f) -1f else x - (x * x * x) * (4f / 27f)

fun dbToGain(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()
val SQRT_HALF = sqrt(0.5f)
