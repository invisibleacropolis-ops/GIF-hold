package com.gifvision.app.ui

import androidx.compose.foundation.Image
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.gifvision.app.ui.components.AdjustmentSlider
import com.gifvision.app.ui.components.FfmpegLogPanel
import com.gifvision.app.ui.state.GifVisionBlendMode
import com.gifvision.app.ui.state.MasterBlendConfig
import com.gifvision.app.ui.state.GifLoopMetadata
import com.gifvision.app.ui.state.PlatformPreview
import com.gifvision.app.ui.state.ShareSetupState
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.abs

/**
 * Screen responsible for combining the outputs from Layer 1 and Layer 2, configuring the final
 * blend, and preparing share collateral. The composable adapts its layout based on the available
 * width so controls, previews, and diagnostics remain visible on larger devices while still
 * stacking vertically on phones. All callbacks are hoisted to `GifVisionViewModel` so rendering and
 * persistence stay centralized.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MasterBlendScreen(
    state: MasterBlendConfig,
    isWideLayout: Boolean,
    onModeChange: (GifVisionBlendMode) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onGenerateMasterBlend: () -> Unit,
    onSaveMasterBlend: () -> Unit,
    onShareMasterBlend: () -> Unit,
    onShareCaptionChange: (String) -> Unit,
    onShareHashtagsChange: (String) -> Unit,
    onShareLoopMetadataChange: (GifLoopMetadata) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val controlsEnabled = state.isEnabled && !state.isGenerating
    val context = LocalContext.current
    val saveEnabled = state.masterGifPath != null && !state.isGenerating
    val shareEnabled = state.masterGifPath != null && !state.isGenerating && !state.shareSetup.isPreparingShare
    val generateLabel = if (state.masterGifPath == null) "Generate Master Blend" else "Regenerate Master Blend"

    val masterControls: @Composable () -> Unit = {
        ElevatedCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "Master Blend", style = MaterialTheme.typography.titleLarge)

                if (!state.isEnabled) {
                    Text(
                        text = "Generate blends for Layer 1 and Layer 2 to unlock the master controls.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (state.isGenerating) {
                    Text(
                        text = "Master blend rendering in progress…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                var expanded by remember { mutableStateOf(false) }
                var menuWidth by remember { mutableStateOf(0.dp) }
                val density = LocalDensity.current

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                menuWidth = with(density) { coordinates.size.width.toDp() }
                            }
                            .clickable(enabled = controlsEnabled) { expanded = !expanded },
                        value = state.mode.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Blend Mode") },
                        enabled = controlsEnabled,
                        trailingIcon = {
                            Icon(
                                imageVector = if (expanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                                contentDescription = if (expanded) "Collapse blend options" else "Expand blend options"
                            )
                        }
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.width(menuWidth)
                    ) {
                        GifVisionBlendMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.displayName) },
                                enabled = controlsEnabled,
                                onClick = {
                                    expanded = false
                                    onModeChange(mode)
                                }
                            )
                        }
                    }
                }

                AdjustmentSlider(
                    label = "Opacity",
                    value = state.opacity,
                    onValueChange = { raw ->
                        val quantized = (raw * 100).roundToInt() / 100f
                        onOpacityChange(quantized)
                    },
                    valueRange = 0f..1f,
                    steps = 100,
                    enabled = controlsEnabled,
                    valueFormatter = { value -> "%.2f".format(value) }
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onGenerateMasterBlend,
                        enabled = controlsEnabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(generateLabel)
                    }
                    OutlinedButton(
                        onClick = onSaveMasterBlend,
                        enabled = saveEnabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save")
                    }
                    Button(
                        onClick = onShareMasterBlend,
                        enabled = shareEnabled,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.shareSetup.isPreparingShare) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Preparing…")
                        } else {
                            Text("Share")
                        }
                    }
                }

                if (state.isGenerating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    val previewCard: @Composable () -> Unit = {
        Card(colors = CardDefaults.cardColors()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "Master Blend Preview", style = MaterialTheme.typography.titleLarge)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.masterGifPath.isNullOrBlank()) {
                        Text(
                            text = "Generate Master Blend to preview",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        val imageRequest = remember(state.masterGifPath) {
                            val gifFile = File(state.masterGifPath)
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
                        when (painter.state) {
                            is AsyncImagePainter.State.Loading -> {
                                CircularProgressIndicator()
                            }
                            is AsyncImagePainter.State.Error -> {
                                val error = (painter.state as AsyncImagePainter.State.Error).result.throwable
                                Text(
                                    text = error.message ?: "Preview failed to load",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                            }
                            else -> {
                                Image(
                                    painter = painter,
                                    contentDescription = "Master blend preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val shareSetupCard: @Composable () -> Unit = {
        ShareSetupCard(
            shareSetup = state.shareSetup,
            onCaptionChange = onShareCaptionChange,
            onHashtagsChange = onShareHashtagsChange,
            onLoopMetadataChange = onShareLoopMetadataChange
        )
    }

    val logPanel: @Composable () -> Unit = {
        FfmpegLogPanel(
            title = "Master FFmpeg Logs",
            logs = state.ffmpegLogs,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (isWideLayout) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    masterControls()
                    logPanel()
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    previewCard()
                    shareSetupCard()
                }
            }
        } else {
            masterControls()
            previewCard()
            shareSetupCard()
            logPanel()
        }
    }
}

/**
 * Card that gathers social-sharing metadata (caption, hashtags, loop descriptors) and shows live
 * previews for each supported platform. The state flow is hoisted so updates can recompute
 * previews inside the view-model, keeping the UI stateless beyond the provided callbacks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareSetupCard(
    shareSetup: ShareSetupState,
    onCaptionChange: (String) -> Unit,
    onHashtagsChange: (String) -> Unit,
    onLoopMetadataChange: (GifLoopMetadata) -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Social Share Setup", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = shareSetup.caption,
                onValueChange = onCaptionChange,
                label = { Text("Caption") },
                placeholder = { Text("Tell the story behind your loop…") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            OutlinedTextField(
                value = shareSetup.hashtagsInput,
                onValueChange = onHashtagsChange,
                label = { Text("Hashtags") },
                placeholder = { Text("#gifvision #loopmagic") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    val helper = if (shareSetup.hashtags.isEmpty()) {
                        "Separate hashtags with spaces or commas."
                    } else {
                        "Parsed: ${shareSetup.hashtags.joinToString(" ")}"
                    }
                    Text(
                        text = helper,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            if (shareSetup.isPreparingShare) {
                Text(
                    text = "Preparing share intent…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(text = "Loop Metadata", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GifLoopMetadata.entries.forEach { metadata ->
                    FilterChip(
                        selected = shareSetup.loopMetadata == metadata,
                        onClick = { onLoopMetadataChange(metadata) },
                        label = { Text(metadata.displayName) }
                    )
                }
            }

            HorizontalDivider()
            Text(text = "Platform Previews", style = MaterialTheme.typography.titleMedium)
            shareSetup.platformPreviews.forEach { preview ->
                PlatformPreviewCard(preview = preview)
            }
        }
    }
}

/**
 * Renders a read-only card representing how the pending caption/hashtag payload will appear on a
 * specific destination (e.g., Instagram or X). The component highlights truncation limits so
 * marketing teams can tune copy without leaving the app.
 */
@Composable
private fun PlatformPreviewCard(preview: PlatformPreview) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = preview.platform.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = preview.loopMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            if (preview.renderedCaption.isBlank()) {
                Text(
                    text = "Caption preview appears once you add copy or hashtags.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(preview.renderedCaption, style = MaterialTheme.typography.bodyMedium)
                if (preview.isCaptionTruncated) {
                    Text(
                        text = "Preview truncated to ${preview.platform.captionLimit} characters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            val remaining = preview.remainingCharacters
            val characterMessage = if (remaining >= 0) {
                "$remaining characters remaining"
            } else {
                "${abs(remaining)} characters over limit"
            }
            Text(
                text = characterMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (remaining >= 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )

            val hashtagMessage = if (preview.overflowHashtags > 0) {
                "${preview.hashtagCount}/${preview.platform.hashtagLimit} hashtags (${preview.overflowHashtags} over limit)"
            } else {
                "${preview.hashtagCount}/${preview.platform.hashtagLimit} hashtags"
            }
            Text(
                text = hashtagMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (preview.overflowHashtags > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
