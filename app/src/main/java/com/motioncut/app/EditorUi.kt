package com.motioncut.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val Background = Color(0xFF101010)
internal val Panel = Color(0xFF1C1C1C)
internal val PanelRaised = Color(0xFF2A2A2A)
internal val Accent = Color(0xFFB7F397)
internal val TextMain = Color(0xFFF4F4F4)
internal val TextMuted = Color(0xFFAAAAAA)
internal val Danger = Color(0xFFFF8A80)

internal enum class EditorTool(val label: String) {
    TRIM("Trim"),
    SPEED("Speed"),
    AUDIO("Audio"),
    ROTATE("Rotate"),
    CROP("Crop")
}

/** Row of tools along the top of the editing dock. */
@Composable
internal fun ToolTabs(
    selected: EditorTool,
    onSelect: (EditorTool) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        EditorTool.entries.forEach { tool ->
            val isSelected = tool == selected

            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Tab) { onSelect(tool) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = tool.label,
                    color = if (isSelected) Accent else TextMuted,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .height(2.dp)
                        .fillMaxWidth(0.5f)
                        .background(if (isSelected) Accent else Color.Transparent)
                )
            }
        }
    }
}

/** A tappable option: filled when selected, dimmed when disabled. */
@Composable
internal fun OptionChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .heightIn(min = 44.dp)
            .background(if (selected) Accent else PanelRaised, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else TextMain,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

/**
 * Start and end times with one range slider on the whole video. The slider
 * call is unchanged from the version verified on a device; only its
 * surroundings moved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrimPanel(
    range: TrimRange?,
    enabled: Boolean,
    onSliderValues: (startValue: Float, endValue: Float) -> Unit
) {
    val sliderStart = (range?.startMs ?: 0L).toFloat()
    val sliderEnd = (range?.endMs ?: 1L).toFloat()
    val sliderMax = (range?.durationMs ?: 1L).toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "start  ${formatTime(range?.startMs ?: 0L)}",
                color = TextMain,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "end  ${formatTime(range?.endMs ?: 0L)}",
                color = TextMain,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // One track for the whole video: both thumbs share the
        // 0..duration scale, so neither can move the other.
        RangeSlider(
            value = sliderStart..sliderEnd,
            onValueChange = { values ->
                onSliderValues(values.start, values.endInclusive)
            },
            valueRange = 0f..sliderMax,
            enabled = enabled && range != null,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent
            )
        )

        Text(
            text = "selected duration: ${formatTime(range?.lengthMs ?: 0L)}" +
                (range?.let { " of ${formatTime(it.durationMs)}" } ?: ""),
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
internal fun SpeedPanel(
    speed: Float,
    enabled: Boolean,
    clipLengthMs: Long?,
    onSpeed: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SPEED_OPTIONS.forEach { option ->
                OptionChip(
                    label = formatSpeed(option),
                    selected = option == speed,
                    enabled = enabled,
                    onClick = { onSpeed(option) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Text(
            text = if (clipLengthMs != null) {
                val output = EditSettings(speed = speed).outputDurationMs(clipLengthMs)
                "exported length: ${formatTime(output)}"
            } else {
                "import a video to change its speed"
            },
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
internal fun AudioPanel(
    volume: Float,
    muted: Boolean,
    enabled: Boolean,
    onVolume: (Float) -> Unit,
    onMuted: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (muted) "audio muted" else "volume  ${(volume * 100).roundToInt()}%",
                color = TextMain,
                style = MaterialTheme.typography.bodyMedium
            )
            OptionChip(
                label = "Mute",
                selected = muted,
                enabled = enabled,
                onClick = { onMuted(!muted) },
                modifier = Modifier.width(88.dp)
            )
        }

        Slider(
            value = volume,
            onValueChange = onVolume,
            valueRange = 0f..1f,
            enabled = enabled && !muted,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent
            )
        )

        Text(
            text = "changes the clip's original audio",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
internal fun RotatePanel(
    rotationDegrees: Int,
    flipHorizontal: Boolean,
    enabled: Boolean,
    onRotate: () -> Unit,
    onFlip: () -> Unit,
    onReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OptionChip(
                label = "Rotate 90°",
                selected = false,
                enabled = enabled,
                onClick = onRotate,
                modifier = Modifier.weight(1f)
            )
            OptionChip(
                label = "Flip",
                selected = flipHorizontal,
                enabled = enabled,
                onClick = onFlip,
                modifier = Modifier.weight(1f)
            )
            OptionChip(
                label = "Reset",
                selected = false,
                enabled = enabled && (rotationDegrees != 0 || flipHorizontal),
                onClick = onReset,
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            text = "rotated $rotationDegrees° clockwise" +
                (if (flipHorizontal) ", mirrored" else ""),
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
internal fun CropPanel(
    selected: AspectOption,
    enabled: Boolean,
    onSelect: (AspectOption) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AspectOption.entries.forEach { option ->
                OptionChip(
                    label = option.label,
                    selected = option == selected,
                    enabled = enabled,
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Text(
            text = "crops from the centre of the frame",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** Export button, or real progress with Cancel while an export is running. */
@Composable
internal fun ExportBar(
    exporting: Boolean,
    canExport: Boolean,
    progress: Float?,
    label: String,
    canCancel: Boolean,
    onExport: () -> Unit,
    onCancel: () -> Unit
) {
    if (exporting) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent,
                    trackColor = PanelRaised
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent,
                    trackColor = PanelRaised
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = TextMain,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (canCancel) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = Danger)
                    }
                }
            }
        }
    } else {
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = canExport,
            onClick = onExport
        ) {
            Text("Export MP4")
        }
    }
}
