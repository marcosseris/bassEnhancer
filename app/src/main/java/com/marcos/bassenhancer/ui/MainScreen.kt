package com.marcos.bassenhancer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcos.bassenhancer.core.CaptureMode
import com.marcos.bassenhancer.core.HapticEngine
import com.marcos.bassenhancer.core.HapticStyle
import com.marcos.bassenhancer.core.Prefs
import com.marcos.bassenhancer.core.PrefsRepository
import com.marcos.bassenhancer.service.BassService
import com.marcos.bassenhancer.service.RunState
import com.marcos.bassenhancer.service.ServiceState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    prefs: PrefsRepository,
    haptics: HapticEngine,
    onToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val s by prefs.flow.collectAsStateWithLifecycle()
    val runState by ServiceState.state.collectAsStateWithLifecycle()
    val message by ServiceState.message.collectAsStateWithLifecycle()
    val levelDb by ServiceState.levelDb.collectAsStateWithLifecycle()
    val amplitude by ServiceState.amplitude.collectAsStateWithLifecycle()
    val running = runState == RunState.RUNNING

    // Any settings edit while running is pushed straight to the live pipeline.
    fun edit(transform: (Prefs) -> Prefs) {
        prefs.update(transform)
        BassService.notifySettingsChanged(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bass Enhancer") },
                navigationIcon = {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MasterCard(
                running = running,
                runState = runState,
                message = message,
                levelDb = levelDb,
                amplitude = amplitude,
                prefs = s,
                onToggle = onToggle,
            )

            SectionCard("Audio source") {
                ChipRow(
                    options = listOf(
                        CaptureMode.PLAYBACK to "Playback capture",
                        CaptureMode.VISUALIZER to "Visualizer",
                    ),
                    selected = s.captureMode,
                    onSelect = { mode ->
                        if (mode != s.captureMode) {
                            BassService.stop(context)
                            prefs.update { it.copy(captureMode = mode, enabled = false) }
                        }
                    },
                )
                Text(
                    when (s.captureMode) {
                        CaptureMode.PLAYBACK ->
                            "Accurate and recommended. Asks for a one-time screen-capture " +
                                "consent each session. Apps that opt out of capture " +
                                "(Spotify, most DRM video) will read as silence."

                        CaptureMode.VISUALIZER ->
                            "Experimental fallback. No consent prompt and it sees every app, " +
                                "but many Android builds return a flat line. Try it if " +
                                "playback capture stays silent."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            SectionCard("Trigger") {
                SettingSlider(
                    label = "Kick in at",
                    description = "Bass below this level is ignored. Watch the meter above " +
                        "and set the marker just under your music's quiet passages.",
                    value = s.thresholdDb,
                    range = -70f..-5f,
                    valueLabel = "${s.thresholdDb.roundToInt()} dB",
                    onChange = { v -> edit { it.copy(thresholdDb = v) } },
                )
                SettingSlider(
                    label = "Intensity",
                    description = "Overall strength multiplier.",
                    value = s.intensity,
                    range = 0.2f..3f,
                    valueLabel = "${(s.intensity * 100).roundToInt()}%",
                    onChange = { v -> edit { it.copy(intensity = v) } },
                )
                SettingSlider(
                    label = "Maximum strength",
                    description = "Hard ceiling on the actuator, for late-night listening.",
                    value = s.maxAmplitude.toFloat(),
                    range = 40f..255f,
                    valueLabel = "${(s.maxAmplitude / 255f * 100).roundToInt()}%",
                    onChange = { v -> edit { it.copy(maxAmplitude = v.roundToInt()) } },
                )
                SettingSwitch(
                    label = "Adaptive gain",
                    description = "Normalises against recent loudness so quiet tracks still " +
                        "rumble. Turn off for a strictly literal response.",
                    checked = s.adaptiveGain,
                    onChange = { v -> edit { it.copy(adaptiveGain = v) } },
                )
            }

            SectionCard("Frequency band") {
                SettingSlider(
                    label = "Low cut",
                    description = "Ignores rumble below this. 20 Hz keeps sub-bass.",
                    value = s.lowCutHz.toFloat(),
                    range = 10f..120f,
                    valueLabel = "${s.lowCutHz} Hz",
                    onChange = { v ->
                        edit {
                            val low = v.roundToInt()
                            it.copy(
                                lowCutHz = low,
                                highCutHz = maxOf(it.highCutHz, low + 20),
                            )
                        }
                    },
                )
                SettingSlider(
                    label = "High cut",
                    description = "Raise it to feel snares and toms as well as the kick.",
                    value = s.highCutHz.toFloat(),
                    range = 60f..400f,
                    valueLabel = "${s.highCutHz} Hz",
                    onChange = { v ->
                        edit {
                            val high = v.roundToInt()
                            it.copy(
                                highCutHz = high,
                                lowCutHz = minOf(it.lowCutHz, high - 20).coerceAtLeast(10),
                            )
                        }
                    },
                )
            }

            SectionCard("Feel") {
                ChipRow(
                    options = listOf(
                        HapticStyle.HYBRID to "Hybrid",
                        HapticStyle.RUMBLE to "Rumble",
                        HapticStyle.PUNCH to "Punch",
                    ),
                    selected = s.style,
                    onSelect = { v -> edit { it.copy(style = v) } },
                )
                Text(
                    when (s.style) {
                        HapticStyle.HYBRID -> "Continuous rumble with a sharp tick on each kick."
                        HapticStyle.RUMBLE -> "Pure amplitude-tracking rumble, no transients."
                        HapticStyle.PUNCH -> "Silent except for a thump on every kick drum."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                SettingSlider(
                    label = "Attack",
                    description = "How fast the rumble rises. Lower is punchier.",
                    value = s.attackMs.toFloat(),
                    range = 0f..150f,
                    valueLabel = "${s.attackMs} ms",
                    onChange = { v -> edit { it.copy(attackMs = v.roundToInt()) } },
                    enabled = s.style != HapticStyle.PUNCH,
                )
                SettingSlider(
                    label = "Release",
                    description = "How long it takes to fade out. Higher feels smoother.",
                    value = s.releaseMs.toFloat(),
                    range = 20f..800f,
                    valueLabel = "${s.releaseMs} ms",
                    onChange = { v -> edit { it.copy(releaseMs = v.roundToInt()) } },
                    enabled = s.style != HapticStyle.PUNCH,
                )
                SettingSlider(
                    label = "Kick sensitivity",
                    description = "Lower fires on more beats; higher only on hard transients.",
                    value = s.kickSensitivity,
                    range = 1.1f..4f,
                    valueLabel = String.format("%.1fx", s.kickSensitivity),
                    onChange = { v -> edit { it.copy(kickSensitivity = v) } },
                    enabled = s.style != HapticStyle.RUMBLE,
                )
                SettingSlider(
                    label = "Update interval",
                    description = "How often the actuator is refreshed. Higher saves battery, " +
                        "lower tracks the music more tightly.",
                    value = s.updateIntervalMs.toFloat(),
                    range = 16f..120f,
                    valueLabel = "${s.updateIntervalMs} ms",
                    onChange = { v -> edit { it.copy(updateIntervalMs = v.roundToInt()) } },
                )
                OutlinedButton(
                    onClick = { haptics.preview(s) },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Test vibration")
                }
            }

            SectionCard("Battery and behaviour") {
                SettingSwitch(
                    label = "Pause while the screen is off",
                    description = "Stops vibrating with the phone in your pocket.",
                    checked = s.pauseOnScreenOff,
                    onChange = { v -> edit { it.copy(pauseOnScreenOff = v) } },
                )
                SettingSwitch(
                    label = "Pause during calls",
                    description = "Holds off while a voice or video call is active.",
                    checked = s.pauseInCall,
                    onChange = { v -> edit { it.copy(pauseInCall = v) } },
                )
                SettingSlider(
                    label = "Stop below battery",
                    description = "Silently idles when the battery drops this low. Off at 0%.",
                    value = s.batteryFloorPct.toFloat(),
                    range = 0f..50f,
                    steps = 9,
                    valueLabel = if (s.batteryFloorPct == 0) "Off" else "${s.batteryFloorPct}%",
                    onChange = { v -> edit { it.copy(batteryFloorPct = v.roundToInt()) } },
                )
                SettingSwitch(
                    label = "Start on boot",
                    description = "Visualizer mode only. Playback capture always needs one tap " +
                        "after a reboot because Android will not persist the consent.",
                    checked = s.startOnBoot,
                    onChange = { v -> edit { it.copy(startOnBoot = v) } },
                )
            }

            SectionCard("Hardware") {
                Text(
                    buildString {
                        appendLine(
                            if (haptics.isPresent) {
                                "Vibrator: present"
                            } else {
                                "Vibrator: none detected"
                            },
                        )
                        appendLine(
                            if (haptics.hasAmplitudeControl) {
                                "Amplitude control: yes (variable strength)"
                            } else {
                                "Amplitude control: no (on/off only)"
                            },
                        )
                        append(
                            if (haptics.hasPrimitives) {
                                "Haptic primitives: yes (crisp kicks available)"
                            } else {
                                "Haptic primitives: no (falls back to short pulses)"
                            },
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        prefs.resetToDefaults()
                        BassService.notifySettingsChanged(context)
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text("Reset settings")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MasterCard(
    running: Boolean,
    runState: RunState,
    message: String?,
    levelDb: Float,
    amplitude: Float,
    prefs: Prefs,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (running) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running) "Enhancing" else "Off",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        when {
                            running -> "Listening to media output"
                            runState == RunState.ERROR && message != null -> message
                            else -> "Tap to start enhancing bass"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (runState == RunState.ERROR && !running) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(checked = running, onCheckedChange = onToggle)
            }

            Spacer(Modifier.height(16.dp))
            LevelMeter(
                levelDb = levelDb,
                amplitude = amplitude,
                thresholdDb = prefs.thresholdDb,
                active = running,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Bass level",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (running) "${levelDb.roundToInt()} dB" else "--",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!running && runState != RunState.ERROR) {
                Button(
                    onClick = { onToggle(true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Text("Start")
                }
            }
        }
    }
}
