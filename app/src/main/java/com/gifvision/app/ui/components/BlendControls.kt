package com.gifvision.app.ui.components

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.menuAnchor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.gifvision.app.ui.state.GifVisionBlendMode
import java.io.File
import kotlin.math.roundToInt

/**
 * Dropdown wrapper that exposes the available [GifVisionBlendMode] options for both layer-level
 * and master-level blends. The exposed dropdown handles accessibility semantics while hoisting
 * the selected mode through [onModeSelected].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlendModeDropdown(
    mode: GifVisionBlendMode,
    enabled: Boolean,
    onModeSelected: (GifVisionBlendMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = mode.displayName,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Blend Mode") },
            trailingIcon = {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                    contentDescription = if (expanded) "Collapse blend options" else "Expand blend options"
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(type = androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            GifVisionBlendMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayName) },
                    onClick = {
                        expanded = false
                        onModeSelected(option)
                    }
                )
            }
        }
    }
}

/**
 * Opacity slider tuned for blend ratios. Values quantize to two decimal places so FFmpeg receives
 * stable `blend=all_opacity` inputs even when users scrub quickly. The default supporting text
 * mirrors the layer workflow but can be overridden by callers.
 */
@Composable
fun BlendOpacitySlider(
    opacity: Float,
    enabled: Boolean,
    onOpacityChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String = "0.00 hides Stream B · 1.00 fully overlays it"
) {
    AdjustmentSlider(
        label = "Opacity",
        value = opacity,
        onValueChange = { raw ->
            val quantized = (raw * 100).roundToInt() / 100f
            onOpacityChange(quantized)
        },
        valueRange = 0f..1f,
        steps = 100,
        enabled = enabled,
        valueFormatter = { value -> "%.2f".format(value) },
        supportingText = supportingText,
        modifier = modifier
    )
}

/**
 * Primary CTA that dispatches the blend request. The label defaults to the layer copy but can be
 * overridden to support the master blend wording.
 */
@Composable
fun GenerateBlendButton(
    enabled: Boolean,
    onGenerate: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Generate Blended GIF"
) {
    Button(
        onClick = onGenerate,
        enabled = enabled,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(label)
    }
}

/**
 * Visualizes the most recent blend output. Handles empty, loading, success, and error states so
 * callers can simply provide the filesystem [path] produced by the coordinator.
 */
@Composable
fun BlendPreviewThumbnail(
    path: String?,
    modifier: Modifier = Modifier,
    emptyStateText: String = "No blended GIF yet",
    contentDescription: String = "Blend preview"
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
        contentAlignment = Alignment.Center
    ) {
        if (path.isNullOrBlank()) {
            Text(
                text = emptyStateText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else {
            val imageRequest = remember(path) {
                val gifFile = File(path)
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
                        contentDescription = contentDescription,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}
