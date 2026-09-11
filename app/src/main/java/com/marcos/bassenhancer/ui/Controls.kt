package com.marcos.bassenhancer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onChange: (Float) -> Unit,
    steps: Int = 0,
    description: String? = null,
    enabled: Boolean = true,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
fun SettingSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

/** Bar meter of the live band level, so the threshold slider can be set by eye. */
@Composable
fun LevelMeter(levelDb: Float, amplitude: Float, thresholdDb: Float, active: Boolean) {
    val normalized = ((levelDb + 90f) / 90f).coerceIn(0f, 1f)
    val thresholdPos = ((thresholdDb + 90f) / 90f).coerceIn(0f, 1f)
    val animated by animateFloatAsState(if (active) normalized else 0f, label = "meter")
    val amp by animateFloatAsState(if (active) amplitude else 0f, label = "amp")

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.secondary),
            )
            Box(
                Modifier
                    .fillMaxWidth(thresholdPos)
                    .fillMaxSize(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.006f)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.error),
                )
            }
        }
        Box(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(amp)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
