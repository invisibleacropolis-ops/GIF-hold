package com.gifvision.app.ui.components

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.RowScope
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import java.io.File

/**
 * Generic preview card that renders a GIF (when available) alongside optional action buttons
 * such as regenerate or save. Layer stream previews and the master preview both consume this
 * component so styling and progress indicators stay aligned across surfaces.
 */
@Composable
fun GifPreviewCard(
    title: String,
    previewPath: String?,
    modifier: Modifier = Modifier,
    thumbnail: ImageBitmap? = null,
    emptyStateText: String = "Generate a GIF to preview the output.",
    isGenerating: Boolean = false,
    onGenerate: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    generateEnabled: Boolean = onGenerate != null && !isGenerating,
    saveEnabled: Boolean = onSave != null && previewPath != null && !isGenerating,
    generateLabel: String = if (previewPath != null) "Regenerate" else "Generate",
    progressMessage: String? = null,
    actionContent: (RowScope.() -> Unit)? = null
) {
    val context = LocalContext.current
    ElevatedCard(modifier = modifier) {
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
                when {
                    previewPath != null -> {
                        val imageRequest = remember(previewPath) {
                            val gifFile = File(previewPath)
                            ImageRequest.Builder(context)
                                .data(gifFile)
                                .crossfade(true)
                                .apply {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        decoderFactory(ImageDecoderDecoder.Factory())
                                    } else {
                                        decoderFactory(GifDecoder.Factory())
                                    }
                                }
                                .build()
                        }
                        val painter = rememberAsyncImagePainter(model = imageRequest)
                        when (val painterState = painter.state) {
                            is AsyncImagePainter.State.Loading -> {
                                CircularProgressIndicator()
                            }

                            is AsyncImagePainter.State.Error -> {
                                Text(
                                    text = painterState.result.throwable.message
                                        ?: "Preview failed to load",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                            }

                            else -> {
                                Image(
                                    painter = painter,
                                    contentDescription = "$title preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }

                    thumbnail != null -> {
                        Image(
                            bitmap = thumbnail,
                            contentDescription = "$title placeholder",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    else -> {
                        Text(
                            text = emptyStateText,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val hasActions = onGenerate != null || onSave != null || actionContent != null
            if (hasActions) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    onGenerate?.let {
                        Button(
                            onClick = it,
                            enabled = generateEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(generateLabel)
                        }
                    }

                    onSave?.let {
                        OutlinedButton(
                            onClick = it,
                            enabled = saveEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save")
                        }
                    }

                    actionContent?.invoke(this)
                }
            }

            if (isGenerating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                progressMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
