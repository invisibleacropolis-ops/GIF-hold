package com.gifvision.app.ui.layer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.gifvision.app.ui.components.BlendControlsAvailability
import com.gifvision.app.ui.components.BlendControlsCard
import com.gifvision.app.ui.components.BlendPreviewThumbnail
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
    val availability = BlendControlsAvailability(
        controlsEnabled = streamAReady && streamBReady && !isGenerating,
        generateEnabled = (streamAReady || streamBReady) && !isGenerating,
        isGenerating = isGenerating
    )

    BlendControlsCard(
        title = LayerCopy.BLEND_PREVIEW_TITLE,
        availability = availability,
        mode = layerState.blendState.mode,
        opacity = layerState.blendState.opacity,
        onModeChange = onBlendModeChange,
        onOpacityChange = onBlendOpacityChange,
        onGenerateBlend = onGenerateBlend,
        infoContent = {
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
        },
        footerContent = {
            BlendPreviewThumbnail(path = layerState.blendState.blendedGifPath)
        }
    )
}
