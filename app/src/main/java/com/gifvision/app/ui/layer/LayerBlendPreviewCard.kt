package com.gifvision.app.ui.layer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gifvision.app.ui.components.BlendModeDropdown
import com.gifvision.app.ui.components.BlendOpacitySlider
import com.gifvision.app.ui.components.BlendPreviewThumbnail
import com.gifvision.app.ui.components.GenerateBlendButton
import com.gifvision.app.ui.resources.LayerCopy
import com.gifvision.app.ui.state.GifVisionBlendMode
import com.gifvision.app.ui.state.Layer

/**
 * Aggregates the layer blend controls including mode selection, opacity slider, and action button.
 * Enablement mirrors the readiness of Stream A/B outputs so FFmpeg jobs are never dispatched
 * without prerequisite renders.
 */
@Composable
internal fun BlendPreviewCard(
    layerState: Layer,
    onBlendModeChange: (GifVisionBlendMode) -> Unit,
    onBlendOpacityChange: (Float) -> Unit,
    onGenerateBlend: () -> Unit
) {
    val streamAReady = !layerState.streamA.generatedGifPath.isNullOrBlank()
    val streamBReady = !layerState.streamB.generatedGifPath.isNullOrBlank()
    val isGenerating = layerState.blendState.isGenerating
    val blendControlsEnabled = streamAReady && streamBReady && !isGenerating
    val generateEnabled = (streamAReady || streamBReady) && !isGenerating

    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = LayerCopy.BLEND_PREVIEW_TITLE, style = MaterialTheme.typography.titleLarge)

            when {
                streamAReady && streamBReady -> Unit
                streamAReady || streamBReady -> {
                    val readyStream = if (streamAReady) "Stream A" else "Stream B"
                    Text(
                        text = LayerCopy.singleStreamReady(readyStream),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Text(
                        text = LayerCopy.BLEND_REQUIREMENT_HINT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            BlendModeDropdown(
                mode = layerState.blendState.mode,
                enabled = blendControlsEnabled,
                onModeSelected = onBlendModeChange
            )

            BlendOpacitySlider(
                opacity = layerState.blendState.opacity,
                enabled = blendControlsEnabled,
                onOpacityChange = onBlendOpacityChange
            )

            GenerateBlendButton(
                enabled = generateEnabled,
                onGenerate = onGenerateBlend
            )

            if (isGenerating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            BlendPreviewThumbnail(path = layerState.blendState.blendedGifPath)
        }
    }
}
