package com.gifvision.app.ui.layer

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gifvision.app.ui.state.Layer
import com.gifvision.app.ui.state.LogSeverity

/**
 * Presents the current source clip metadata for the layer and offers affordances to browse or
 * replace the video. The card surfaces cached thumbnails, resolution, duration, and file size so
 * editors can verify they imported the expected media before adjusting streams.
 */
@Composable
internal fun UploadCard(
    layerState: Layer,
    onBrowseForClip: () -> Unit,
    onAppendLog: (String, LogSeverity) -> Unit
) {
    val context = LocalContext.current
    val clip = layerState.sourceClip
    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Upload Source", style = MaterialTheme.typography.titleLarge)
            if (clip == null) {
                Text(
                    text = "No clip selected yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    clip.thumbnail?.let { preview ->
                        Image(
                            bitmap = preview,
                            contentDescription = "Source thumbnail for ${layerState.title}",
                            modifier = Modifier
                                .height(96.dp)
                                .aspectRatio(16f / 9f),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = clip.displayName, style = MaterialTheme.typography.titleMedium)
                        formatResolution(clip.width, clip.height)?.let { resolution ->
                            Text(text = "Resolution: $resolution", style = MaterialTheme.typography.bodySmall)
                        }
                        clip.durationMs?.let { duration ->
                            Text(text = "Duration: ${formatDuration(duration)}", style = MaterialTheme.typography.bodySmall)
                        }
                        clip.sizeBytes?.let { bytes ->
                            Text(
                                text = "Size: ${Formatter.formatShortFileSize(context, bytes)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        clip.mimeType?.let { type ->
                            Text(text = "Format: $type", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBrowseForClip) {
                    Text("Browse Files")
                }
                if (clip != null) {
                    TextButton(
                        onClick = {
                            onAppendLog("Change clip requested for ${layerState.title}", LogSeverity.Info)
                            onBrowseForClip()
                        }
                    ) {
                        Text("Change")
                    }
                }
            }
            Text(
                text = "Drag-and-drop support arrives with the media coordinator implementation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun formatResolution(width: Int?, height: Int?): String? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    return "${width}×${height}"
}
