package com.gifvision.app.ui.components.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared scaffold for GIF preview cards surfaced on layer and master blend screens. The
 * implementation handles common chrome (title, preview surface, action row, progress/status copy)
 * so feature-specific cards only provide their preview media + actions.
 */
/**
 * Controls where the preview surface should render relative to the action column inside
 * [GifPreviewCard]. Layer blend cards historically place their preview beneath the controls
 * while stream/master cards keep the preview at the top, so the enum makes the placement
 * explicit while retaining a single scaffold implementation.
 */
enum class PreviewPlacement {
    ABOVE_ACTIONS,
    BELOW_ACTIONS
}

@Composable
fun GifPreviewCard(
    title: String,
    previewContent: @Composable () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    isGenerating: Boolean = false,
    statusMessage: String? = null,
    statusMessageColor: Color? = null,
    previewPlacement: PreviewPlacement = PreviewPlacement.ABOVE_ACTIONS
) {
    ElevatedCard(modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            val renderPreview: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center
                ) {
                    previewContent()
                }
            }
            when (previewPlacement) {
                PreviewPlacement.ABOVE_ACTIONS -> {
                    renderPreview()
                    actions()
                }

                PreviewPlacement.BELOW_ACTIONS -> {
                    actions()
                    renderPreview()
                }
            }
            if (isGenerating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusMessageColor
                        ?: if (isGenerating) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                )
            }
        }
    }
}
