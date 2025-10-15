package com.gifvision.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.RowScope
import com.gifvision.app.ui.state.GifVisionBlendMode

/**
 * Shared wrapper that composes the blend controls used by both the layer and master screens.
 * The card keeps enablement, progress indicators, and helper messaging consistent while still
 * exposing hooks for feature-specific secondary actions or supporting content.
 */
@Composable
fun BlendControlsCard(
    title: String,
    mode: GifVisionBlendMode,
    opacity: Float,
    onModeSelected: (GifVisionBlendMode) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onGenerateBlend: () -> Unit,
    controlsEnabled: Boolean,
    generateEnabled: Boolean,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
    statusMessage: String? = null,
    sliderSupportingText: String = "0.00 hides Stream B · 1.00 fully overlays it",
    generateLabel: String = "Generate Blended GIF",
    actionContent: (RowScope.() -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null
) {
    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)

            statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            BlendModeDropdown(
                mode = mode,
                enabled = controlsEnabled,
                onModeSelected = onModeSelected
            )

            BlendOpacitySlider(
                opacity = opacity,
                enabled = controlsEnabled,
                onOpacityChange = onOpacityChange,
                supportingText = sliderSupportingText
            )

            val hasActions = generateEnabled || actionContent != null
            if (hasActions) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    GenerateBlendButton(
                        enabled = generateEnabled,
                        onGenerate = onGenerateBlend,
                        label = generateLabel,
                        modifier = Modifier.weight(1f)
                    )
                    actionContent?.invoke(this)
                }
            }

            if (isGenerating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            supportingContent?.invoke()
        }
    }
}
