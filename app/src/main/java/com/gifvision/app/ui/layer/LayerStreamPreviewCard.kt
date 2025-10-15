package com.gifvision.app.ui.layer

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
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.gifvision.app.ui.state.Stream
import java.io.File

/**
 * Shows the most recent render output for a specific stream. Falls back to the imported thumbnail
 * while no GIF exists and displays contextual messaging so editors understand why the preview might
 * be empty. Coil drives GIF playback directly inside the card. Each layer has two of these cards -
 * one for Stream A and one for Stream B, both visible simultaneously.
 */
@Composable
internal fun StreamPreviewCard(
    streamState: Stream,
    onRequestStreamRender: () -> Unit,
    onSaveStream: () -> Unit
) {
    val context = LocalContext.current
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Stream ${streamState.stream.name} Preview", style = MaterialTheme.typography.titleLarge)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center
            ) {
                when {
                    streamState.generatedGifPath != null -> {
                        val imageRequest = remember(streamState.generatedGifPath) {
                            val gifFile = File(streamState.generatedGifPath)
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
                        Image(
                            painter = painter,
                            contentDescription = "Generated GIF preview for Stream ${streamState.stream.name}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    streamState.previewThumbnail != null -> {
                        Image(
                            bitmap = streamState.previewThumbnail,
                            contentDescription = "Source thumbnail preview for Stream ${streamState.stream.name}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    else -> {
                        Text(
                            text = "Generate a GIF to preview the output.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onRequestStreamRender,
                    enabled = !streamState.isGenerating,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (streamState.generatedGifPath != null) "Regenerate" else "Generate")
                }

                OutlinedButton(
                    onClick = onSaveStream,
                    enabled = streamState.generatedGifPath != null && !streamState.isGenerating,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }

            if (streamState.isGenerating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Rendering Stream ${streamState.stream.name}...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
