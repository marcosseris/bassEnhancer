package com.marcos.bassenhancer.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class CaptureMode {
    /** MediaProjection playback capture. Accurate, but needs a consent prompt and
     *  apps that opt out (Spotify, some DRM players) are silent to it. */
    PLAYBACK,

    /** Legacy global-mix Visualizer. No consent prompt, but many devices and ROMs
     *  return silence -- offered as a fallback only. */
    VISUALIZER,
}

enum class HapticStyle {
    /** Continuous amplitude-modulated rumble that tracks the bass envelope. */
    RUMBLE,

    /** Discrete thumps on detected kicks; quiet in between. */
    PUNCH,

    /** Rumble underneath, with a sharper primitive on each kick. */
    HYBRID,
}

data class Prefs(
    val enabled: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.PLAYBACK,
    /** Band level, in dBFS, below which nothing is played. "Kick in at". */
    val thresholdDb: Float = -34f,
    val intensity: Float = 1.0f,
    val maxAmplitude: Int = 255,
    val lowCutHz: Int = 20,
    val highCutHz: Int = 160,
    val attackMs: Int = 8,
    val releaseMs: Int = 140,
    val updateIntervalMs: Int = 32,
    val style: HapticStyle = HapticStyle.HYBRID,
    val kickSensitivity: Float = 1.8f,
    val adaptiveGain: Boolean = true,
    val pauseOnScreenOff: Boolean = false,
    val pauseInCall: Boolean = true,
    val batteryFloorPct: Int = 0,
    val startOnBoot: Boolean = false,
) {
    /** Amplitude floor: below this, the actuator would only click, so skip it. */
    val minAmplitude: Int get() = 12
}

class PrefsRepository private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<Prefs> = _flow

    val current: Prefs get() = _flow.value

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _flow.value = read() }

    init {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun update(transform: (Prefs) -> Prefs) {
        val next = transform(current)
        sp.edit().apply {
            putBoolean(K_ENABLED, next.enabled)
            putString(K_MODE, next.captureMode.name)
            putFloat(K_THRESHOLD, next.thresholdDb)
            putFloat(K_INTENSITY, next.intensity)
            putInt(K_MAX_AMP, next.maxAmplitude)
            putInt(K_LOW, next.lowCutHz)
            putInt(K_HIGH, next.highCutHz)
            putInt(K_ATTACK, next.attackMs)
            putInt(K_RELEASE, next.releaseMs)
            putInt(K_INTERVAL, next.updateIntervalMs)
            putString(K_STYLE, next.style.name)
            putFloat(K_KICK, next.kickSensitivity)
            putBoolean(K_AGC, next.adaptiveGain)
            putBoolean(K_SCREEN_OFF, next.pauseOnScreenOff)
            putBoolean(K_IN_CALL, next.pauseInCall)
            putInt(K_BATTERY, next.batteryFloorPct)
            putBoolean(K_BOOT, next.startOnBoot)
        }.apply()
        _flow.value = next
    }

    fun resetToDefaults() = update { Prefs(enabled = it.enabled, captureMode = it.captureMode) }

    private fun read(): Prefs {
        val d = Prefs()
        return Prefs(
            enabled = sp.getBoolean(K_ENABLED, d.enabled),
            captureMode = enumOrDefault(sp.getString(K_MODE, null), d.captureMode),
            thresholdDb = sp.getFloat(K_THRESHOLD, d.thresholdDb),
            intensity = sp.getFloat(K_INTENSITY, d.intensity),
            maxAmplitude = sp.getInt(K_MAX_AMP, d.maxAmplitude),
            lowCutHz = sp.getInt(K_LOW, d.lowCutHz),
            highCutHz = sp.getInt(K_HIGH, d.highCutHz),
            attackMs = sp.getInt(K_ATTACK, d.attackMs),
            releaseMs = sp.getInt(K_RELEASE, d.releaseMs),
            updateIntervalMs = sp.getInt(K_INTERVAL, d.updateIntervalMs),
            style = enumOrDefault(sp.getString(K_STYLE, null), d.style),
            kickSensitivity = sp.getFloat(K_KICK, d.kickSensitivity),
            adaptiveGain = sp.getBoolean(K_AGC, d.adaptiveGain),
            pauseOnScreenOff = sp.getBoolean(K_SCREEN_OFF, d.pauseOnScreenOff),
            pauseInCall = sp.getBoolean(K_IN_CALL, d.pauseInCall),
            batteryFloorPct = sp.getInt(K_BATTERY, d.batteryFloorPct),
            startOnBoot = sp.getBoolean(K_BOOT, d.startOnBoot),
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    companion object {
        private const val NAME = "bass_enhancer"
        private const val K_ENABLED = "enabled"
        private const val K_MODE = "capture_mode"
        private const val K_THRESHOLD = "threshold_db"
        private const val K_INTENSITY = "intensity"
        private const val K_MAX_AMP = "max_amplitude"
        private const val K_LOW = "low_cut"
        private const val K_HIGH = "high_cut"
        private const val K_ATTACK = "attack_ms"
        private const val K_RELEASE = "release_ms"
        private const val K_INTERVAL = "interval_ms"
        private const val K_STYLE = "style"
        private const val K_KICK = "kick_sensitivity"
        private const val K_AGC = "adaptive_gain"
        private const val K_SCREEN_OFF = "pause_screen_off"
        private const val K_IN_CALL = "pause_in_call"
        private const val K_BATTERY = "battery_floor"
        private const val K_BOOT = "start_on_boot"

        @Volatile
        private var instance: PrefsRepository? = null

        fun get(context: Context): PrefsRepository =
            instance ?: synchronized(this) {
                instance ?: PrefsRepository(context).also { instance = it }
            }
    }
}
