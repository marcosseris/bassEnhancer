package com.marcos.bassenhancer.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direct-form-1 biquad. Coefficients follow the RBJ audio EQ cookbook.
 *
 * Deliberately allocation-free: every audio callback reuses the same instance,
 * which is what keeps the analysis thread off the GC's radar.
 */
internal class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun lowPass(sampleRate: Int, freq: Float, q: Float) {
        val w0 = 2.0 * PI * freq.coerceIn(10f, sampleRate / 2.2f) / sampleRate
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        set(
            b0 = ((1.0 - cw) / 2.0) / a0,
            b1 = (1.0 - cw) / a0,
            b2 = ((1.0 - cw) / 2.0) / a0,
            a1 = (-2.0 * cw) / a0,
            a2 = (1.0 - alpha) / a0,
        )
    }

    fun highPass(sampleRate: Int, freq: Float, q: Float) {
        val w0 = 2.0 * PI * freq.coerceIn(5f, sampleRate / 2.2f) / sampleRate
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        set(
            b0 = ((1.0 + cw) / 2.0) / a0,
            b1 = (-(1.0 + cw)) / a0,
            b2 = ((1.0 + cw) / 2.0) / a0,
            a1 = (-2.0 * cw) / a0,
            a2 = (1.0 - alpha) / a0,
        )
    }

    private fun set(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        this.b0 = b0.toFloat()
        this.b1 = b1.toFloat()
        this.b2 = b2.toFloat()
        this.a1 = a1.toFloat()
        this.a2 = a2.toFloat()
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}

/** One-pole envelope follower with independent attack and release times. */
internal class Envelope(private var attackCoef: Float = 0f, private var releaseCoef: Float = 0f) {
    var value = 0f
        private set

    fun configure(attackMs: Float, releaseMs: Float, rateHz: Float) {
        attackCoef = coef(attackMs, rateHz)
        releaseCoef = coef(releaseMs, rateHz)
    }

    fun push(x: Float): Float {
        val c = if (x > value) attackCoef else releaseCoef
        value += (1f - c) * (x - value)
        return value
    }

    fun reset() {
        value = 0f
    }

    private fun coef(ms: Float, rateHz: Float): Float {
        if (ms <= 0f) return 0f
        return exp(-1.0 / ((ms / 1000.0) * rateHz)).toFloat()
    }
}

internal fun rms(buf: FloatArray, from: Int, count: Int): Float {
    var sum = 0.0
    for (i in from until from + count) {
        val v = buf[i]
        sum += v.toDouble() * v
    }
    return sqrt(sum / count).toFloat()
}
