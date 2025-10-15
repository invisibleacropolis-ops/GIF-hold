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
@Composable
fun GifPreviewCard(
    title: String,
    previewContent: @Composable () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    isGenerating: Boolean = false,
    statusMessage: String? = null,
    statusMessageColor: Color? = null
) {
    ElevatedCard(modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center
            ) {
                previewContent()
            }
            actions()
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
