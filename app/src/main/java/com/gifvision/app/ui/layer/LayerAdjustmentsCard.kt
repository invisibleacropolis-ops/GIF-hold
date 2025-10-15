package com.gifvision.app.ui.layer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.gifvision.app.ui.components.AdjustmentSlider
import com.gifvision.app.ui.components.AdjustmentSwitch
import com.gifvision.app.ui.components.AdjustmentValidation
import com.gifvision.app.ui.state.Layer
import com.gifvision.app.ui.state.AdjustmentSettings
import com.gifvision.app.ui.state.StreamSelection
import kotlin.math.roundToInt

/**
 * Hosts the stream toggle (Stream A/B) and the tabbed adjustment surface. By funnelling all
 * mutation callbacks through this card we keep the adjustments declarative and ensure the active
 * stream's settings remain in sync with the view-model state.
 */
@Composable
internal fun AdjustmentsCard(
    layerState: Layer,
    onStreamSelected: (StreamSelection) -> Unit,
    onAdjustmentsChange: (StreamSelection, (AdjustmentSettings) -> AdjustmentSettings) -> Unit
) {
    val streamState = when (layerState.activeStream) {
        StreamSelection.A -> layerState.streamA
        StreamSelection.B -> layerState.streamB
    }

    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Adjustments", style = MaterialTheme.typography.titleLarge)

            Spacer(modifier = Modifier.height(12.dp))
            StreamSelector(
                activeStream = layerState.activeStream,
                onStreamSelected = onStreamSelected
            )

            Spacer(modifier = Modifier.height(16.dp))
            AdjustmentTabContent(
                layerId = layerState.id,
                activeStream = layerState.activeStream,
                adjustments = streamState.adjustments,
                onAdjustmentsChange = onAdjustmentsChange
            )
        }
    }
}

/** Enumerates the high-level adjustment tabs. */
private enum class AdjustmentTab(val title: String) {
    Quality("Quality & Size"),
    Text("Text Overlay"),
    Color("Color & Tone"),
    Experimental("Experimental Filters")
}

/**
 * Material tab container used to switch between the major adjustment groups. Tab selections are
 * stored per-stream via remembered state so flipping between Stream A/B restores the last active
 * section for each stream.
 */
@Composable
private fun AdjustmentTabContent(
    layerId: Int,
    activeStream: StreamSelection,
    adjustments: AdjustmentSettings,
    onAdjustmentsChange: (StreamSelection, (AdjustmentSettings) -> AdjustmentSettings) -> Unit
) {
    val tabSelections = remember(layerId) {
        mutableStateMapOf(
            StreamSelection.A to AdjustmentTab.Quality,
            StreamSelection.B to AdjustmentTab.Quality
        )
    }
    val selectedTab = tabSelections[activeStream] ?: AdjustmentTab.Quality

    val selectedIndex = AdjustmentTab.entries.indexOf(selectedTab)
    TabRow(selectedTabIndex = selectedIndex) {
        AdjustmentTab.entries.forEachIndexed { index, tab ->
            val isSelected = index == selectedIndex
            Tab(
                selected = isSelected,
                onClick = { tabSelections[activeStream] = tab },
                text = { Text(tab.title) }
            )
        }
    }

    when (selectedTab) {
        AdjustmentTab.Quality -> QualitySection(activeStream, adjustments, onAdjustmentsChange)
        AdjustmentTab.Text -> TextOverlaySection(activeStream, adjustments, onAdjustmentsChange)
        AdjustmentTab.Color -> ColorSection(activeStream, adjustments, onAdjustmentsChange)
        AdjustmentTab.Experimental -> ExperimentalSection(activeStream, adjustments, onAdjustmentsChange)
    }
}

/**
 * Segmented control that lets editors switch between Stream A and Stream B. Only the inactive
 * stream remains tappable, which keeps the current stream's adjustments safe from accidental
 * double taps.
 */
@Composable
private fun StreamSelector(activeStream: StreamSelection, onStreamSelected: (StreamSelection) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StreamSelection.values().forEach { stream ->
            OutlinedButton(
                onClick = { onStreamSelected(stream) },
                enabled = stream != activeStream
            ) {
                Text(text = "Stream ${stream.name}")
            }
        }
    }
}

/**
 * Groups the core quality dials (resolution, palette size, frame rate, duration). Validation keeps
 * values within FFmpeg-safe bounds and surfaces warning copy when editors push into extreme ranges
 * that could inflate file sizes.
 */
@Composable
private fun QualitySection(
    stream: StreamSelection,
    adjustments: AdjustmentSettings,
    onAdjustmentsChange: (StreamSelection, (AdjustmentSettings) -> AdjustmentSettings) -> Unit
) {
    Spacer(modifier = Modifier.height(12.dp))
    AdjustmentSlider(
        label = "Resolution",
        tooltip = "Scales the exported GIF width relative to the imported clip.",
        value = adjustments.resolutionPercent,
        onValueChange = { newValue ->
            onAdjustmentsChange(stream) { it.copy(resolutionPercent = newValue) }
        },
        valueRange = 0.2f..1.0f,
        steps = 8,
        valueFormatter = { "${(it * 100).roundToInt()}%" },
        supportingText = "Lower percentages shrink file size while preserving aspect ratio.",
        validation = if (adjustments.resolutionPercent in 0.2f..1.0f) null else {
            AdjustmentValidation(true, "Resolution must stay between 20% and 100% of source width.")
        }
    )
    AdjustmentSlider(
        label = "Max Colors",
        tooltip = "Controls the GIF palette size. Smaller palettes reduce file size but can cause banding.",
        value = adjustments.maxColors.toFloat(),
        onValueChange = { newValue ->
            onAdjustmentsChange(stream) { it.copy(maxColors = newValue.roundToInt().coerceIn(2, 256)) }
        },
        valueRange = 2f..256f,
        steps = 254,
        valueFormatter = { "${it.roundToInt()}" },
        supportingText = "GIFs support 2-256 colors per frame.",
        validation = if (adjustments.maxColors in 2..256) null else {
            AdjustmentValidation(true, "Palette must remain between 2 and 256 colors.")
        }
    )
    AdjustmentSlider(
        label = "Framerate (fps)",
        tooltip = "Sets animation smoothness. Higher frame rates increase output size.",
        value = adjustments.frameRate,
        onValueChange = { newValue ->
            onAdjustmentsChange(stream) { it.copy(frameRate = newValue) }
        },
        valueRange = 5f..60f,
        steps = 55,
        valueFormatter = { "${it.roundToInt()}" },
        supportingText = "Recommended range: 5–48 fps for balance between smoothness and size.",
        validation = when {
            adjustments.frameRate < 5f -> AdjustmentValidation(true, "Minimum frame rate is 5 fps.")
            adjustments.frameRate > 60f -> AdjustmentValidation(true, "Maximum frame rate is 60 fps.")
            adjustments.frameRate > 48f -> AdjustmentValidation(false, "Frame rates above 48 fps create heavy files.")
            else -> null
        }
    )
    AdjustmentSlider(
        label = "Clip Duration (s)",
        tooltip = "Determines how many seconds of the trimmed clip render into the GIF.",
        value = adjustments.clipDurationSeconds,
        onValueChange = { newValue ->
            onAdjustmentsChange(stream) { it.copy(clipDurationSeconds = newValue) }
        },
        valueRange = 0.5f..30f,
        steps = 295,
        valueFormatter = { String.format("%.1f", it) },
        supportingText = "Trim range sets the ceiling; durations cap at 30 seconds per stream.",
        validation = when {
            adjustments.clipDurationSeconds < 0.5f -> AdjustmentValidation(true, "Minimum duration is 0.5 seconds.")
            adjustments.clipDurationSeconds > 30f -> AdjustmentValidation(true, "Maximum duration is 30 seconds.")
            else -> null
        }
    )
}

/**
 * Collects overlay copy and typography controls. The section enforces font size and hex color
 * validation so FFmpeg's `drawtext` filter receives clean inputs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextOverlaySection(
    stream: StreamSelection,
    adjustments: AdjustmentSettings,
    onAdjustmentsChange: (StreamSelection, (AdjustmentSettings) -> AdjustmentSettings) -> Unit
) {
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = adjustments.textOverlay,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(textOverlay = newValue) } },
        label = { Text("Overlay Text") },
        keyboardOptions = KeyboardOptions.Default.copy(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done
        ),
        supportingText = {
            Text(
                text = "Add optional caption text. Keep it brief for readability.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    )
    AdjustmentSlider(
        label = "Font Size",
        tooltip = "Sets overlay text size in scalable pixels.",
        value = adjustments.fontSizeSp.toFloat(),
        onValueChange = { newValue ->
            onAdjustmentsChange(stream) { it.copy(fontSizeSp = newValue.roundToInt().coerceIn(50, 216)) }
        },
        valueRange = 50f..216f,
        steps = 64,
        valueFormatter = null
    )

    var expandedFontColor by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expandedFontColor,
        onExpandedChange = { expandedFontColor = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        OutlinedTextField(
            value = adjustments.fontColorHex.replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            label = { Text("Font Color") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedFontColor)
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expandedFontColor,
            onDismissRequest = { expandedFontColor = false }
        ) {
            DropdownMenuItem(
                text = { Text("White") },
                onClick = {
                    onAdjustmentsChange(stream) { it.copy(fontColorHex = "white") }
                    expandedFontColor = false
                }
            )
            DropdownMenuItem(
                text = { Text("Black") },
                onClick = {
                    onAdjustmentsChange(stream) { it.copy(fontColorHex = "black") }
                    expandedFontColor = false
                }
            )
        }
    }
}

/**
 * Provides the primary color grading sliders along with per-channel balance controls. Each slider
 * maps directly to FFmpeg filter expressions, so clamping the ranges prevents invalid expressions
 * from reaching the coordinator.
 */
@Composable
private fun ColorSection(
    stream: StreamSelection,
    adjustments: AdjustmentSettings,
    onAdjustmentsChange: (StreamSelection, (AdjustmentSettings) -> AdjustmentSettings) -> Unit
) {
    Spacer(modifier = Modifier.height(12.dp))
    AdjustmentSlider(
        label = "Brightness",
        tooltip = "Raises or lowers luminance across the entire frame.",
        value = adjustments.brightness,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(brightness = newValue) } },
        valueRange = -1f..1f,
        steps = 19,
        supportingText = "Negative values darken, positive values lighten.",
        validation = if (adjustments.brightness in -1f..1f) null else {
            AdjustmentValidation(true, "Brightness stays between -1 and 1.")
        }
    )
    AdjustmentSlider(
        label = "Contrast",
        tooltip = "Expands or compresses tonal range.",
        value = adjustments.contrast,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(contrast = newValue) } },
        valueRange = 0.5f..2f,
        steps = 15,
        supportingText = "Default 1.0 preserves original contrast.",
        validation = if (adjustments.contrast in 0.5f..2f) null else {
            AdjustmentValidation(true, "Contrast must stay between 0.5× and 2×.")
        }
    )
    AdjustmentSlider(
        label = "Saturation",
        tooltip = "Adjusts overall color intensity.",
        value = adjustments.saturation,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(saturation = newValue) } },
        valueRange = 0f..2f,
        steps = 20,
        supportingText = "0 removes color, >1 boosts vibrancy.",
        validation = if (adjustments.saturation in 0f..2f) null else {
            AdjustmentValidation(true, "Saturation must stay between 0 and 2.")
        }
    )
    AdjustmentSlider(
        label = "Hue",
        tooltip = "Shifts all colors around the spectrum.",
        value = adjustments.hue,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(hue = newValue) } },
        valueRange = -180f..180f,
        steps = 359,
        valueFormatter = { "${it.roundToInt()}°" },
        supportingText = "Wraps seamlessly at 360°. Useful for stylized looks.",
        validation = if (adjustments.hue in -180f..180f) null else {
            AdjustmentValidation(true, "Hue shift stays within ±180°.")
        }
    )
    AdjustmentSlider(
        label = "Sepia",
        tooltip = "Applies a brown tonal wash for vintage aesthetics.",
        value = adjustments.sepia,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(sepia = newValue) } },
        valueRange = 0f..1f,
        steps = 10,
        supportingText = "Blend intensity from 0 (off) to 1 (full).",
        validation = if (adjustments.sepia in 0f..1f) null else {
            AdjustmentValidation(true, "Sepia intensity must stay between 0 and 1.")
        }
    )
    AdjustmentSlider(
        label = "Color Balance - Red",
        tooltip = "Offsets the red channel for tint correction.",
        value = adjustments.colorBalanceRed,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(colorBalanceRed = newValue) } },
        valueRange = 0f..2f,
        steps = 20,
        supportingText = "Values above 1 warm the image; below 1 cool it.",
        validation = if (adjustments.colorBalanceRed in 0f..2f) null else {
            AdjustmentValidation(true, "Red balance stays within 0–2.")
        }
    )
    AdjustmentSlider(
        label = "Color Balance - Green",
        tooltip = "Offsets the green channel to balance mid-tones.",
        value = adjustments.colorBalanceGreen,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(colorBalanceGreen = newValue) } },
        valueRange = 0f..2f,
        steps = 20,
        supportingText = "Use subtle adjustments (±0.1) for natural results.",
        validation = if (adjustments.colorBalanceGreen in 0f..2f) null else {
            AdjustmentValidation(true, "Green balance stays within 0–2.")
        }
    )
    AdjustmentSlider(
        label = "Color Balance - Blue",
        tooltip = "Offsets the blue channel for cooler or warmer casts.",
        value = adjustments.colorBalanceBlue,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(colorBalanceBlue = newValue) } },
        valueRange = 0f..2f,
        steps = 20,
        supportingText = "Combine with red/green balance for precise grading.",
        validation = if (adjustments.colorBalanceBlue in 0f..2f) null else {
            AdjustmentValidation(true, "Blue balance stays within 0–2.")
        }
    )
}

/**
 * Hosts stylized effects that manipulate the FFmpeg filter graph beyond traditional grading. The
 * section exposes helper copy describing recommended ranges to keep renders performant.
 */
@Composable
private fun ExperimentalSection(
    stream: StreamSelection,
    adjustments: AdjustmentSettings,
    onAdjustmentsChange: (StreamSelection, (AdjustmentSettings) -> AdjustmentSettings) -> Unit
) {
    Spacer(modifier = Modifier.height(12.dp))
    AdjustmentSlider(
        label = "Pixellate",
        tooltip = "Creates a retro pixel-art effect by reducing resolution. Higher values create larger pixel blocks.",
        value = adjustments.pixellate,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(pixellate = newValue) } },
        valueRange = 0f..50f,
        steps = 49,
        valueFormatter = { "${it.roundToInt()}" },
        supportingText = "0 = off, 1-10 = subtle, 11-30 = moderate, 31-50 = extreme pixelation.",
        validation = if (adjustments.pixellate in 0f..50f) null else {
            AdjustmentValidation(true, "Pixellate stays between 0 and 50.")
        }
    )
    AdjustmentSlider(
        label = "Color Cycle Speed",
        tooltip = "Rotates hues over time for psychedelic loops. Max speed = 3 full rotations per second!",
        value = adjustments.colorCycleSpeed,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(colorCycleSpeed = newValue) } },
        valueRange = 0f..3f,
        steps = 30,
        valueFormatter = { String.format("%.1f rot/s", it) },
        supportingText = "0 = off, 0.5-1 = subtle shift, 1.5-2 = moderate, 2.5-3 = extreme psychedelic effect.",
        validation = if (adjustments.colorCycleSpeed in 0f..3f) null else {
            AdjustmentValidation(true, "Color cycle speed stays between 0 and 3 rotations/second.")
        }
    )
    AdjustmentSlider(
        label = "Motion Trails",
        tooltip = "Creates intense motion blur by mixing multiple frames together. Extreme values create Bruce Lee-style action trails.",
        value = adjustments.motionTrails,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(motionTrails = newValue) } },
        valueRange = 0f..1f,
        steps = 10,
        supportingText = "Low = subtle ghosting (2 frames), High = extreme motion trails (10 frames).",
        validation = if (adjustments.motionTrails in 0f..1f) null else {
            AdjustmentValidation(true, "Motion Trails stays between 0 and 1.")
        }
    )
    AdjustmentSlider(
        label = "Sharpen",
        tooltip = "Enhances edge contrast for crisper detail.",
        value = adjustments.sharpen,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(sharpen = newValue) } },
        valueRange = 0f..1f,
        steps = 10,
        supportingText = "Use sparingly to avoid halos.",
        validation = if (adjustments.sharpen in 0f..1f) null else {
            AdjustmentValidation(true, "Sharpen stays between 0 and 1.")
        }
    )
    AdjustmentSwitch(
        label = "Edge Detect",
        tooltip = "Converts frames into stylized white-on-black line art.",
        checked = adjustments.edgeDetectEnabled,
        onCheckedChange = { isChecked -> onAdjustmentsChange(stream) { it.copy(edgeDetectEnabled = isChecked) } },
        supportingText = "Shows only detected edges when enabled."
    )
    if (adjustments.edgeDetectEnabled) {
        AdjustmentSlider(
            label = "Edge Threshold",
            tooltip = "Controls edge detection sensitivity. Higher values detect more edges.",
            value = adjustments.edgeDetectThreshold,
            onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(edgeDetectThreshold = newValue) } },
            valueRange = 0f..1f,
            steps = 20,
            supportingText = "Lower = only strong edges, Higher = more edge detail.",
            validation = if (adjustments.edgeDetectThreshold in 0f..1f) null else {
                AdjustmentValidation(true, "Edge Threshold stays between 0 and 1.")
            }
        )
        AdjustmentSlider(
            label = "Edge Boost",
            tooltip = "Enhances brightness and contrast of detected edges.",
            value = adjustments.edgeDetectBoost,
            onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(edgeDetectBoost = newValue) } },
            valueRange = 0f..1f,
            steps = 10,
            supportingText = "Makes white edges 'pop' more against the black background.",
            validation = if (adjustments.edgeDetectBoost in 0f..1f) null else {
                AdjustmentValidation(true, "Edge Boost stays between 0 and 1.")
            }
        )
    }
    AdjustmentSwitch(
        label = "Negate Colors",
        tooltip = "Inverts every color channel for a negative-film look.",
        checked = adjustments.negateColors,
        onCheckedChange = { isChecked -> onAdjustmentsChange(stream) { it.copy(negateColors = isChecked) } },
        supportingText = "Pair with color cycling for neon results."
    )
    AdjustmentSwitch(
        label = "Flip Horizontal",
        tooltip = "Mirrors the frame across the vertical axis.",
        checked = adjustments.flipHorizontal,
        onCheckedChange = { isChecked -> onAdjustmentsChange(stream) { it.copy(flipHorizontal = isChecked) } },
        supportingText = "Helpful when reorienting mirrored footage."
    )
    AdjustmentSwitch(
        label = "Flip Vertical",
        tooltip = "Flips the GIF upside down.",
        checked = adjustments.flipVertical,
        onCheckedChange = { isChecked -> onAdjustmentsChange(stream) { it.copy(flipVertical = isChecked) } },
        supportingText = "Combine with horizontal flip for 180° rotation without FFmpeg filters."
    )
}
