package com.marcos.bassenhancer.core

import android.os.SystemClock
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Turns interleaved PCM into two numbers the haptic engine cares about:
 * a smoothed 0..1 bass level, and discrete "kick" onsets.
 *
 * The whole pipeline is a band-pass cascade plus a couple of one-pole envelopes,
 * which costs a few microseconds per audio buffer. No FFT, no allocations in the
 * hot path -- the point of the app is to be invisible while it runs.
 */
class BassAnalyzer(private val sampleRate: Int) {

    /** Sub-block size: ~6 ms at 44.1 kHz, fine enough to catch a kick transient. */
    private val hop = max(64, sampleRate / 172)

    private val hp1 = Biquad()
    private val hp2 = Biquad()
    private val lp1 = Biquad()
    private val lp2 = Biquad()

    private val slowEnv = Envelope()
    private val fastEnv = Envelope()
    private val outEnv = Envelope()

    private val work = FloatArray(hop)
    private var filled = 0

    private var thresholdLin = 0.02f
    private var kickSensitivity = 1.8f
    private var adaptiveGain = false
    private var intensity = 1f

    private var agcPeak = 0.05f
    private var lastKickAt = 0L

    /** Smoothed, threshold-and-gain mapped level in 0..1. Read from the haptic thread. */
    @Volatile
    var level: Float = 0f
        private set

    /** Raw band level in dBFS, purely for the UI meter. */
    @Volatile
    var levelDb: Float = -90f
        private set

    /** Set by [process] when a transient is detected; the consumer clears it. */
    @Volatile
    var pendingKick: Float = 0f

    private val blockRate: Float get() = sampleRate.toFloat() / hop

    fun configure(s: Prefs) {
        val low = s.lowCutHz.toFloat()
        val high = max(s.highCutHz.toFloat(), low + 10f)
        hp1.highPass(sampleRate, low, 0.7071f)
        hp2.highPass(sampleRate, low, 0.7071f)
        lp1.lowPass(sampleRate, high, 0.7071f)
        lp2.lowPass(sampleRate, high, 0.7071f)

        thresholdLin = 10f.pow(s.thresholdDb / 20f)
        kickSensitivity = s.kickSensitivity
        adaptiveGain = s.adaptiveGain
        intensity = s.intensity

        outEnv.configure(s.attackMs.toFloat(), s.releaseMs.toFloat(), blockRate)
        fastEnv.configure(1f, 45f, blockRate)
        slowEnv.configure(120f, 450f, blockRate)
    }

    fun reset() {
        hp1.reset(); hp2.reset(); lp1.reset(); lp2.reset()
        slowEnv.reset(); fastEnv.reset(); outEnv.reset()
        filled = 0
        agcPeak = 0.05f
        level = 0f
        levelDb = -90f
        pendingKick = 0f
    }

    /**
     * @param pcm interleaved 16-bit samples
     * @param count valid sample count in [pcm]
     * @param channels 1 or 2
     */
    fun process(pcm: ShortArray, count: Int, channels: Int) {
        var i = 0
        while (i + channels <= count) {
            var mono = 0f
            for (c in 0 until channels) mono += pcm[i + c] / 32768f
            mono /= channels
            i += channels

            val band = lp2.process(lp1.process(hp2.process(hp1.process(mono))))
            work[filled++] = band
            if (filled == hop) {
                consumeBlock()
                filled = 0
            }
        }
    }

    private fun consumeBlock() {
        val r = rms(work, 0, hop)
        levelDb = if (r <= 1e-6f) -90f else (20f * log10(r)).coerceIn(-90f, 0f)

        val fast = fastEnv.push(r)
        val slow = slowEnv.push(r)

        // Onset: a sharp rise above the running average, with a refractory gap so a
        // sustained bass line does not machine-gun the actuator.
        if (fast > thresholdLin && fast > slow * kickSensitivity) {
            val now = SystemClock.uptimeMillis()
            if (now - lastKickAt > 90L) {
                lastKickAt = now
                pendingKick = min(1f, (fast / max(slow, 1e-5f) - 1f) / 3f).coerceAtLeast(0.25f)
            }
        }

        // Map band energy to 0..1. Everything under the threshold stays silent so the
        // phone is not buzzing through quiet passages.
        var norm = if (adaptiveGain) {
            agcPeak = max(r, agcPeak * 0.9985f).coerceAtLeast(0.01f)
            (r - thresholdLin) / max(agcPeak - thresholdLin, 1e-4f)
        } else {
            (r - thresholdLin) / max(1f - thresholdLin, 1e-4f)
        }
        norm = norm.coerceIn(0f, 1f)
        // Slight expansion: emphasises the difference between a thump and a hum.
        norm = norm.pow(0.65f) * intensity
        level = outEnv.push(norm.coerceIn(0f, 1.5f)).coerceIn(0f, 1f)
    }
}
