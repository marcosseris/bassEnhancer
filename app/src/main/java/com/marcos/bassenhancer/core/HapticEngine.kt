package com.marcos.bassenhancer.core

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Drives the actuator from a 0..1 level plus discrete kicks.
 *
 * Continuous haptics on Android means re-issuing short one-shots: each `vibrate`
 * call supersedes the previous one, so a slightly-longer-than-the-tick one-shot
 * re-issued every tick reads as an unbroken rumble whose strength we can change.
 * We skip re-issues when the amplitude has barely moved, which keeps the binder
 * traffic down to a handful of calls a second on steady material.
 */
private const val KICK_RING_MS = 40L

class HapticEngine(context: Context) {

    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** Media usage keeps the rumble alive under ring/DND modes that mute alerts. */
    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    val hasAmplitudeControl: Boolean = vibrator?.hasAmplitudeControl() == true

    val hasPrimitives: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator?.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            VibrationEffect.Composition.PRIMITIVE_THUD,
        ) == true

    val isPresent: Boolean = vibrator?.hasVibrator() == true

    private var lastAmplitude = 0
    private var lastIssuedAt = 0L
    private var currentEndsAt = 0L

    /**
     * @param level 0..1 bass envelope
     * @param kick  0..1 transient strength, or 0 for none
     */
    fun render(level: Float, kick: Float, prefs: Prefs) {
        val v = vibrator ?: return
        val now = SystemClock.uptimeMillis()

        if (kick > 0f && prefs.style != HapticStyle.RUMBLE) {
            playKick(v, kick, prefs)
            // A kick owns the actuator for this tick. Resuming the rumble now would
            // issue a one-shot that supersedes the transient we just played, so the
            // punch would never be felt; the next tick picks the rumble back up.
            currentEndsAt = now + KICK_RING_MS
            lastIssuedAt = now
            lastAmplitude = 0
            return
        }

        if (prefs.style == HapticStyle.PUNCH) return

        val target = amplitudeFor(level, prefs)
        if (target < prefs.minAmplitude) {
            if (lastAmplitude != 0) {
                v.cancel()
                lastAmplitude = 0
                currentEndsAt = 0
            }
            return
        }

        val interval = prefs.updateIntervalMs
        val stillRunning = now < currentEndsAt
        val drifted = abs(target - lastAmplitude) > 8
        val stale = now - lastIssuedAt >= interval
        if (stillRunning && !drifted && !stale) return

        // Overlap the tick so consecutive one-shots butt up against each other.
        val duration = (interval * 2.2f).toLong().coerceIn(24L, 400L)
        val effect = if (hasAmplitudeControl) {
            VibrationEffect.createOneShot(duration, target)
        } else {
            VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrate(v, effect)
        lastAmplitude = target
        lastIssuedAt = now
        currentEndsAt = now + duration
    }

    private fun amplitudeFor(level: Float, prefs: Prefs): Int {
        if (level <= 0f) return 0
        val ceiling = prefs.maxAmplitude.coerceIn(1, 255)
        return (level * ceiling).roundToInt().coerceIn(0, 255)
    }

    private fun playKick(v: Vibrator, strength: Float, prefs: Prefs) {
        val scale = (strength * prefs.intensity).coerceIn(0.05f, 1f) *
            (prefs.maxAmplitude / 255f)
        val effect = if (hasPrimitives && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val primitive = if (prefs.style == HapticStyle.PUNCH) {
                VibrationEffect.Composition.PRIMITIVE_THUD
            } else {
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            }
            VibrationEffect.startComposition()
                .addPrimitive(primitive, scale.coerceIn(0.05f, 1f))
                .compose()
        } else {
            val amp = (scale * 255).roundToInt().coerceIn(1, 255)
            if (hasAmplitudeControl) {
                VibrationEffect.createOneShot(28L, amp)
            } else {
                VibrationEffect.createOneShot(28L, VibrationEffect.DEFAULT_AMPLITUDE)
            }
        }
        vibrate(v, effect)
    }

    /** One-off buzz used by the "Test" button so settings can be felt immediately. */
    fun preview(prefs: Prefs) {
        val v = vibrator ?: return
        val amp = prefs.maxAmplitude.coerceIn(1, 255)
        val timings = longArrayOf(0, 90, 60, 130, 60, 200)
        val amps = intArrayOf(0, amp / 3, 0, (amp * 2) / 3, 0, amp)
        val effect = if (hasAmplitudeControl) {
            VibrationEffect.createWaveform(timings, amps, -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
        vibrate(v, effect)
    }

    private fun vibrate(v: Vibrator, effect: VibrationEffect) {
        try {
            @Suppress("DEPRECATION")
            v.vibrate(effect, audioAttributes)
        } catch (_: Exception) {
            // A vibrate() can throw if the actuator is taken over by the system;
            // dropping the frame is always better than killing the service.
        }
    }

    fun stop() {
        lastAmplitude = 0
        currentEndsAt = 0
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
    }
}
