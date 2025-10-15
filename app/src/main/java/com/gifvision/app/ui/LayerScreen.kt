package com.gifvision.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.gifvision.app.ui.components.AdjustmentSlider
import com.gifvision.app.ui.components.AdjustmentSwitch
import com.gifvision.app.ui.components.AdjustmentValidation
import com.gifvision.app.ui.components.BlendModeDropdown
import com.gifvision.app.ui.components.BlendOpacitySlider
import com.gifvision.app.ui.components.BlendPreviewThumbnail
import com.gifvision.app.ui.components.FfmpegLogPanel
import com.gifvision.app.ui.components.GenerateBlendButton
import com.gifvision.app.ui.state.AdjustmentSettings
import com.gifvision.app.ui.state.GifVisionBlendMode
import com.gifvision.app.ui.state.Layer
import com.gifvision.app.ui.state.LogSeverity
import com.gifvision.app.ui.state.Stream
import com.gifvision.app.ui.state.StreamSelection
import java.util.concurrent.TimeUnit
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Screen dedicated to managing a single layer (Stream A + Stream B). It wires the import picker,
 * media preview, adjustment tabs, blend controls, and FFmpeg diagnostics into a cohesive layout
 * while hoisting mutations back to `GifVisionViewModel`. The surface adapts between a single column
 * and dual-column canvas based on [isWideLayout] so larger devices can review previews and logs in
 * parallel with adjustments.
 */
@Composable
fun LayerScreen(
    layerState: Layer,
    isWideLayout: Boolean,
    onStreamSelected: (StreamSelection) -> Unit,
    onAdjustmentsChange: (StreamSelection, (AdjustmentSettings) -> AdjustmentSettings) -> Unit,
    onImportClip: (Uri) -> Unit,
    onTrimRangeChange: (StreamSelection, Long, Long) -> Unit,
    onPlaybackPositionChange: (StreamSelection, Long) -> Unit,
    onPlayPauseChange: (StreamSelection, Boolean) -> Unit,
    onRequestStreamRender: (StreamSelection) -> Unit,
    onSaveStreamOutput: (StreamSelection) -> Unit,
    onBlendModeChange: (GifVisionBlendMode) -> Unit,
    onBlendOpacityChange: (Float) -> Unit,
    onGenerateBlend: () -> Unit,
    onAppendLog: (String, LogSeverity) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    
    // The active stream state is used for the video preview player and adjustments panel
    val activeStreamState = when (layerState.activeStream) {
        StreamSelection.A -> layerState.streamA
        StreamSelection.B -> layerState.streamB
    }
    
    val context = LocalContext.current
    val openDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (security: SecurityException) {
                onAppendLog(
                    "Persistable permission denied: ${security.message ?: "unknown"}",
                    LogSeverity.Error
                )
            }
            onImportClip(uri)
        }
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
                    UploadCard(
                        layerState = layerState,
                        onBrowseForClip = {
                            onAppendLog("Browsing for clip on ${layerState.title}", LogSeverity.Info)
                            openDocumentLauncher.launch(arrayOf("video/*"))
                        },
                        onAppendLog = onAppendLog
                    )
                    VideoPreviewCard(
                        layerState = layerState,
                        streamState = activeStreamState,
                        onTrimChange = { start, end -> onTrimRangeChange(activeStreamState.stream, start, end) },
                        onPlaybackChange = { position -> onPlaybackPositionChange(activeStreamState.stream, position) },
                        onPlayPauseChange = { playing -> onPlayPauseChange(activeStreamState.stream, playing) }
                    )
                    AdjustmentsCard(
                        layerState = layerState,
                        onStreamSelected = onStreamSelected,
                        onAdjustmentsChange = onAdjustmentsChange
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    StreamPreviewCard(
                        streamState = layerState.streamA,
                        onRequestStreamRender = { onRequestStreamRender(StreamSelection.A) },
                        onSaveStream = { onSaveStreamOutput(StreamSelection.A) }
                    )
                    StreamPreviewCard(
                        streamState = layerState.streamB,
                        onRequestStreamRender = { onRequestStreamRender(StreamSelection.B) },
                        onSaveStream = { onSaveStreamOutput(StreamSelection.B) }
                    )
                    BlendPreviewCard(
                        layerState = layerState,
                        onBlendModeChange = onBlendModeChange,
                        onBlendOpacityChange = onBlendOpacityChange,
                        onGenerateBlend = onGenerateBlend
                    )
                    FfmpegLogPanel(
                        title = "${layerState.title} FFmpeg Logs",
                        logs = layerState.ffmpegLogs,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            UploadCard(
                layerState = layerState,
                onBrowseForClip = {
                    onAppendLog("Browsing for clip on ${layerState.title}", LogSeverity.Info)
                    openDocumentLauncher.launch(arrayOf("video/*"))
                },
                onAppendLog = onAppendLog
            )
            VideoPreviewCard(
                layerState = layerState,
                streamState = activeStreamState,
                onTrimChange = { start, end -> onTrimRangeChange(activeStreamState.stream, start, end) },
                onPlaybackChange = { position -> onPlaybackPositionChange(activeStreamState.stream, position) },
                onPlayPauseChange = { playing -> onPlayPauseChange(activeStreamState.stream, playing) }
            )
            AdjustmentsCard(
                layerState = layerState,
                onStreamSelected = onStreamSelected,
                onAdjustmentsChange = onAdjustmentsChange
            )
            StreamPreviewCard(
                streamState = layerState.streamA,
                onRequestStreamRender = { onRequestStreamRender(StreamSelection.A) },
                onSaveStream = { onSaveStreamOutput(StreamSelection.A) }
            )
            StreamPreviewCard(
                streamState = layerState.streamB,
                onRequestStreamRender = { onRequestStreamRender(StreamSelection.B) },
                onSaveStream = { onSaveStreamOutput(StreamSelection.B) }
            )
            BlendPreviewCard(
                layerState = layerState,
                onBlendModeChange = onBlendModeChange,
                onBlendOpacityChange = onBlendOpacityChange,
                onGenerateBlend = onGenerateBlend
            )
            FfmpegLogPanel(
                title = "${layerState.title} FFmpeg Logs",
                logs = layerState.ffmpegLogs,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Presents the current source clip metadata for the layer and offers affordances to browse or
 * replace the video. The card surfaces cached thumbnails, resolution, duration, and file size so
 * editors can verify they imported the expected media before adjusting streams.
 */
@Composable
private fun UploadCard(
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

/**
 * Embeds an ExoPlayer instance that mirrors the selected stream's trim and playback state. The
 * composable owns the preview transport controls, syncs playback position back to the view-model,
 * and clamps scrubber/trim ranges so FFmpeg always receives consistent start/end times.
 */
@Composable
private fun VideoPreviewCard(
    layerState: Layer,
    streamState: Stream,
    onTrimChange: (Long, Long) -> Unit,
    onPlaybackChange: (Long) -> Unit,
    onPlayPauseChange: (Boolean) -> Unit
) {
    val clip = layerState.sourceClip
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Video Preview", style = MaterialTheme.typography.titleLarge)
            if (clip == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Select a video to enable playback.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val context = LocalContext.current
                val player = remember(clip.uri) {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(clip.uri))
                        prepare()
                        playWhenReady = streamState.isPlaying
                    }
                }
                var fallbackDurationMs by remember(clip.uri) { mutableStateOf(clip.durationMs ?: 0L) }
                DisposableEffect(player) {
                    onDispose { player.release() }
                }
                LaunchedEffect(streamState.isPlaying) {
                    player.playWhenReady = streamState.isPlaying
                }
                LaunchedEffect(streamState.playbackPositionMs) {
                    val desired = streamState.playbackPositionMs
                    if (kotlin.math.abs(player.currentPosition - desired) > 40) {
                        player.seekTo(desired)
                    }
                }
                LaunchedEffect(player) {
                    if (fallbackDurationMs <= 0L) {
                        while (isActive) {
                            val duration = player.duration
                            if (duration > 0) {
                                fallbackDurationMs = duration
                                break
                            }
                            delay(200)
                        }
                    }
                }
                LaunchedEffect(streamState.trimStartMs, streamState.trimEndMs, player) {
                    val start = streamState.trimStartMs
                    val end = streamState.trimEndMs
                    val current = player.currentPosition
                    if (current < start || current > end) {
                        player.seekTo(start)
                        onPlaybackChange(start)
                    }
                }
                LaunchedEffect(player, streamState.isPlaying, streamState.trimEndMs) {
                    while (isActive) {
                        val position = player.currentPosition
                        onPlaybackChange(position)
                        val trimEnd = streamState.trimEndMs
                        val trimStart = streamState.trimStartMs
                        if (streamState.isPlaying && trimEnd > trimStart && position >= trimEnd) {
                            onPlayPauseChange(false)
                            player.pause()
                            player.seekTo(trimStart)
                            onPlaybackChange(trimStart)
                        }
                        delay(250)
                    }
                }

                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .semantics {
                                this@semantics.set(
                                    SemanticsProperties.ContentDescription,
                                    listOf("Video preview player")
                                )
                            },
                        factory = { viewContext ->
                            PlayerView(viewContext).apply {
                                useController = false
                                this.player = player
                            }
                        },
                        update = { it.player = player }
                    )

                val durationMs = when {
                    clip.durationMs != null && clip.durationMs > 0 -> clip.durationMs
                    streamState.trimEndMs > 0 -> streamState.trimEndMs
                    else -> fallbackDurationMs
                }
                val trimStart = streamState.trimStartMs.coerceIn(0L, durationMs)
                val trimEnd = streamState.trimEndMs.takeIf { it > trimStart } ?: durationMs
                val playbackPosition = streamState.playbackPositionMs.coerceIn(trimStart, trimEnd)
                val durationSeconds = durationMs / 1000f
                val trimStartSeconds = trimStart / 1000f
                val trimEndSeconds = trimEnd / 1000f
                val playbackSeconds = playbackPosition / 1000f

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { onPlayPauseChange(true) },
                        enabled = !streamState.isPlaying
                    ) {
                        Text("Play")
                    }
                    Button(
                        onClick = { onPlayPauseChange(false) },
                        enabled = streamState.isPlaying,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Pause")
                    }
                    TextButton(
                        onClick = {
                            onPlayPauseChange(false)
                            onPlaybackChange(trimStart)
                            player.seekTo(trimStart)
                        }
                    ) {
                        Text("Reset")
                    }
                    Spacer(modifier = Modifier.weight(1f, fill = true))
                    Text(
                        text = "${formatDuration(playbackPosition)} / ${formatDuration(trimEnd)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Slider(
                    value = playbackSeconds,
                    onValueChange = { newValue ->
                        val newPosition = (newValue * 1000f).roundToLong().coerceIn(trimStart, trimEnd)
                        player.seekTo(newPosition)
                        onPlaybackChange(newPosition)
                    },
                    valueRange = trimStartSeconds..trimEndSeconds,
                    colors = SliderDefaults.colors(activeTrackColor = MaterialTheme.colorScheme.primary)
                )

                if (durationMs > 0L) {
                    RangeSlider(
                        value = trimStartSeconds..trimEndSeconds,
                        onValueChange = { range ->
                            val startMs = (range.start * 1000f).roundToLong().coerceAtLeast(0L)
                            val endMs = (range.endInclusive * 1000f).roundToLong().coerceAtLeast(startMs + 1)
                            onTrimChange(startMs, endMs)
                            if (playbackPosition !in startMs..endMs) {
                                player.seekTo(startMs)
                                onPlaybackChange(startMs)
                            }
                        },
                        valueRange = 0f..max(durationSeconds, 0.5f),
                        steps = max(durationSeconds.roundToInt() - 1, 0)
                    )
                    Text(
                        text = "Trim: ${formatDuration(trimStart)} → ${formatDuration(trimEnd)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Hosts the stream toggle (Stream A/B) and the tabbed adjustment surface. By funnelling all
 * mutation callbacks through this card we keep the adjustments declarative and ensure the active
 * stream's settings remain in sync with the view-model state.
 */
@Composable
private fun AdjustmentsCard(
    layerState: Layer,
    onStreamSelected: (StreamSelection) -> Unit,
    onAdjustmentsChange: (StreamSelection, (AdjustmentSettings) -> AdjustmentSettings) -> Unit
) {
    val streamState = when (layerState.activeStream) {
        StreamSelection.A -> layerState.streamA
        StreamSelection.B -> layerState.streamB
    }

    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Adjustments", style = MaterialTheme.typography.titleLarge)

            Spacer(modifier = Modifier.height(12.dp))
            StreamSelector(
                activeStream = layerState.activeStream,
        onStreamSelected = onStreamSelected
    )

            Spacer(modifier = Modifier.height(16.dp))
            AdjustmentTabContent(
                layerId = layerState.id,
                activeStream = layerState.activeStream,
                adjustments = streamState.adjustments,
                onAdjustmentsChange = onAdjustmentsChange
            )
        }
    }
}

/** Enumerates the high-level adjustment tabs. */
private enum class AdjustmentTab(val title: String) {
    Quality("Quality & Size"),
    Text("Text Overlay"),
    Color("Color & Tone"),
    Experimental("Experimental Filters")
}

/**
 * Material tab container used to switch between the major adjustment groups. Tab selections are
 * stored per-stream via remembered state so flipping between Stream A/B restores the last active
 * section for each stream.
 */
@Composable
private fun AdjustmentTabContent(
    layerId: Int,
    activeStream: StreamSelection,
    adjustments: AdjustmentSettings,
    onAdjustmentsChange: (StreamSelection, (AdjustmentSettings) -> AdjustmentSettings) -> Unit
) {
    val tabSelections = remember(layerId) {
        mutableStateMapOf(
            StreamSelection.A to AdjustmentTab.Quality,
            StreamSelection.B to AdjustmentTab.Quality
        )
    }
    val selectedTab = tabSelections[activeStream] ?: AdjustmentTab.Quality

    val selectedIndex = AdjustmentTab.entries.indexOf(selectedTab)
    TabRow(selectedTabIndex = selectedIndex) {
        AdjustmentTab.entries.forEachIndexed { index, tab ->
            val isSelected = index == selectedIndex
            Tab(
                selected = isSelected,
                onClick = { tabSelections[activeStream] = tab },
                text = { Text(tab.title) }
            )
        }
    }

    when (selectedTab) {
        AdjustmentTab.Quality -> QualitySection(activeStream, adjustments, onAdjustmentsChange)
        AdjustmentTab.Text -> TextOverlaySection(activeStream, adjustments, onAdjustmentsChange)
        AdjustmentTab.Color -> ColorSection(activeStream, adjustments, onAdjustmentsChange)
        AdjustmentTab.Experimental -> ExperimentalSection(activeStream, adjustments, onAdjustmentsChange)
    }
}

/**
 * Segmented control that lets editors switch between Stream A and Stream B. Only the inactive
 * stream remains tappable, which keeps the current stream's adjustments safe from accidental
 * double taps.
 */
@Composable
private fun StreamSelector(activeStream: StreamSelection, onStreamSelected: (StreamSelection) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StreamSelection.values().forEach { stream ->
            OutlinedButton(
                onClick = { onStreamSelected(stream) },
                enabled = stream != activeStream
            ) {
                Text(text = "Stream ${stream.name}")
            }
        }
    }
}

/**
 * Groups the core quality dials (resolution, palette size, frame rate, duration). Validation keeps
 * values within FFmpeg-safe bounds and surfaces warning copy when editors push into extreme ranges
 * that could inflate file sizes.
 */
@Composable
private fun QualitySection(
    stream: StreamSelection,
    adjustments: AdjustmentSettings,
    onAdjustmentsChange: (StreamSelection, (AdjustmentSettings) -> AdjustmentSettings) -> Unit
) {
    Spacer(modifier = Modifier.height(12.dp))
    AdjustmentSlider(
        label = "Resolution",
        tooltip = "Scales the exported GIF width relative to the imported clip.",
        value = adjustments.resolutionPercent,
        onValueChange = { newValue ->
            onAdjustmentsChange(stream) { it.copy(resolutionPercent = newValue) }
        },
        valueRange = 0.2f..1.0f,
        steps = 8,
        valueFormatter = { "${(it * 100).roundToInt()}%" },
        supportingText = "Lower percentages shrink file size while preserving aspect ratio.",
        validation = if (adjustments.resolutionPercent in 0.2f..1.0f) null else {
            AdjustmentValidation(true, "Resolution must stay between 20% and 100% of source width.")
        }
    )
    AdjustmentSlider(
        label = "Max Colors",
        tooltip = "Controls the GIF palette size. Smaller palettes reduce file size but can cause banding.",
        value = adjustments.maxColors.toFloat(),
        onValueChange = { newValue ->
            onAdjustmentsChange(stream) { it.copy(maxColors = newValue.roundToInt().coerceIn(2, 256)) }
        },
        valueRange = 2f..256f,
        steps = 254,
        valueFormatter = { "${it.roundToInt()}" },
        supportingText = "GIFs support 2-256 colors per frame.",
        validation = if (adjustments.maxColors in 2..256) null else {
            AdjustmentValidation(true, "Palette must remain between 2 and 256 colors.")
        }
    )
    AdjustmentSlider(
        label = "Framerate (fps)",
        tooltip = "Sets animation smoothness. Higher frame rates increase output size.",
        value = adjustments.frameRate,
        onValueChange = { newValue ->
            onAdjustmentsChange(stream) { it.copy(frameRate = newValue) }
        },
        valueRange = 5f..60f,
        steps = 55,
        valueFormatter = { "${it.roundToInt()}" },
        supportingText = "Recommended range: 5–48 fps for balance between smoothness and size.",
        validation = when {
            adjustments.frameRate < 5f -> AdjustmentValidation(true, "Minimum frame rate is 5 fps.")
            adjustments.frameRate > 60f -> AdjustmentValidation(true, "Maximum frame rate is 60 fps.")
            adjustments.frameRate > 48f -> AdjustmentValidation(false, "Frame rates above 48 fps create heavy files.")
            else -> null
        }
    )
    AdjustmentSlider(
        label = "Clip Duration (s)",
        tooltip = "Determines how many seconds of the trimmed clip render into the GIF.",
        value = adjustments.clipDurationSeconds,
        onValueChange = { newValue ->
            onAdjustmentsChange(stream) { it.copy(clipDurationSeconds = newValue) }
        },
        valueRange = 0.5f..30f,
        steps = 295,
        valueFormatter = { String.format("%.1f", it) },
        supportingText = "Trim range sets the ceiling; durations cap at 30 seconds per stream.",
        validation = when {
            adjustments.clipDurationSeconds < 0.5f -> AdjustmentValidation(true, "Minimum duration is 0.5 seconds.")
            adjustments.clipDurationSeconds > 30f -> AdjustmentValidation(true, "Maximum duration is 30 seconds.")
            else -> null
        }
    )
}

/**
 * Collects overlay copy and typography controls. The section enforces font size and hex color
 * validation so FFmpeg's `drawtext` filter receives clean inputs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextOverlaySection(
    stream: StreamSelection,
    adjustments: AdjustmentSettings,
    onAdjustmentsChange: (StreamSelection, (AdjustmentSettings) -> AdjustmentSettings) -> Unit
) {
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = adjustments.textOverlay,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(textOverlay = newValue) } },
        label = { Text("Overlay Text") },
        keyboardOptions = KeyboardOptions.Default.copy(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done
        ),
        supportingText = {
            Text(
                text = "Add optional caption text. Keep it brief for readability.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    )
    AdjustmentSlider(
        label = "Font Size",
        tooltip = "Sets overlay text size in scalable pixels.",
        value = adjustments.fontSizeSp.toFloat(),
        onValueChange = { newValue ->
            onAdjustmentsChange(stream) { it.copy(fontSizeSp = newValue.roundToInt().coerceIn(50, 216)) }
        },
        valueRange = 50f..216f,
        steps = 64,
        valueFormatter = null
    )
    
    // Font Color Dropdown
    var expandedFontColor by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expandedFontColor,
        onExpandedChange = { expandedFontColor = it },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        OutlinedTextField(
            value = adjustments.fontColorHex.replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            label = { Text("Font Color") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedFontColor)
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expandedFontColor,
            onDismissRequest = { expandedFontColor = false }
        ) {
            DropdownMenuItem(
                text = { Text("White") },
                onClick = {
                    onAdjustmentsChange(stream) { it.copy(fontColorHex = "white") }
                    expandedFontColor = false
                }
            )
            DropdownMenuItem(
                text = { Text("Black") },
                onClick = {
                    onAdjustmentsChange(stream) { it.copy(fontColorHex = "black") }
                    expandedFontColor = false
                }
            )
        }
    }
}

/**
 * Provides the primary color grading sliders along with per-channel balance controls. Each slider
 * maps directly to FFmpeg filter expressions, so clamping the ranges prevents invalid expressions
 * from reaching the coordinator.
 */
@Composable
private fun ColorSection(
    stream: StreamSelection,
    adjustments: AdjustmentSettings,
    onAdjustmentsChange: (StreamSelection, (AdjustmentSettings) -> AdjustmentSettings) -> Unit
) {
    Spacer(modifier = Modifier.height(12.dp))
    AdjustmentSlider(
        label = "Brightness",
        tooltip = "Raises or lowers luminance across the entire frame.",
        value = adjustments.brightness,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(brightness = newValue) } },
        valueRange = -1f..1f,
        steps = 19,
        supportingText = "Negative values darken, positive values lighten.",
        validation = if (adjustments.brightness in -1f..1f) null else {
            AdjustmentValidation(true, "Brightness stays between -1 and 1.")
        }
    )
    AdjustmentSlider(
        label = "Contrast",
        tooltip = "Expands or compresses tonal range.",
        value = adjustments.contrast,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(contrast = newValue) } },
        valueRange = 0.5f..2f,
        steps = 15,
        supportingText = "Default 1.0 preserves original contrast.",
        validation = if (adjustments.contrast in 0.5f..2f) null else {
            AdjustmentValidation(true, "Contrast must stay between 0.5× and 2×.")
        }
    )
    AdjustmentSlider(
        label = "Saturation",
        tooltip = "Adjusts overall color intensity.",
        value = adjustments.saturation,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(saturation = newValue) } },
        valueRange = 0f..2f,
        steps = 20,
        supportingText = "0 removes color, >1 boosts vibrancy.",
        validation = if (adjustments.saturation in 0f..2f) null else {
            AdjustmentValidation(true, "Saturation must stay between 0 and 2.")
        }
    )
    AdjustmentSlider(
        label = "Hue",
        tooltip = "Shifts all colors around the spectrum.",
        value = adjustments.hue,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(hue = newValue) } },
        valueRange = -180f..180f,
        steps = 359,
        valueFormatter = { "${it.roundToInt()}°" },
        supportingText = "Wraps seamlessly at 360°. Useful for stylized looks.",
        validation = if (adjustments.hue in -180f..180f) null else {
            AdjustmentValidation(true, "Hue shift stays within ±180°.")
        }
    )
    AdjustmentSlider(
        label = "Sepia",
        tooltip = "Applies a brown tonal wash for vintage aesthetics.",
        value = adjustments.sepia,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(sepia = newValue) } },
        valueRange = 0f..1f,
        steps = 10,
        supportingText = "Blend intensity from 0 (off) to 1 (full).",
        validation = if (adjustments.sepia in 0f..1f) null else {
            AdjustmentValidation(true, "Sepia intensity must stay between 0 and 1.")
        }
    )
    AdjustmentSlider(
        label = "Color Balance - Red",
        tooltip = "Offsets the red channel for tint correction.",
        value = adjustments.colorBalanceRed,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(colorBalanceRed = newValue) } },
        valueRange = 0f..2f,
        steps = 20,
        supportingText = "Values above 1 warm the image; below 1 cool it.",
        validation = if (adjustments.colorBalanceRed in 0f..2f) null else {
            AdjustmentValidation(true, "Red balance stays within 0–2.")
        }
    )
    AdjustmentSlider(
        label = "Color Balance - Green",
        tooltip = "Offsets the green channel to balance mid-tones.",
        value = adjustments.colorBalanceGreen,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(colorBalanceGreen = newValue) } },
        valueRange = 0f..2f,
        steps = 20,
        supportingText = "Use subtle adjustments (±0.1) for natural results.",
        validation = if (adjustments.colorBalanceGreen in 0f..2f) null else {
            AdjustmentValidation(true, "Green balance stays within 0–2.")
        }
    )
    AdjustmentSlider(
        label = "Color Balance - Blue",
        tooltip = "Offsets the blue channel for cooler or warmer casts.",
        value = adjustments.colorBalanceBlue,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(colorBalanceBlue = newValue) } },
        valueRange = 0f..2f,
        steps = 20,
        supportingText = "Combine with red/green balance for precise grading.",
        validation = if (adjustments.colorBalanceBlue in 0f..2f) null else {
            AdjustmentValidation(true, "Blue balance stays within 0–2.")
        }
    )
}

/**
 * Hosts stylized effects that manipulate the FFmpeg filter graph beyond traditional grading. The
 * section exposes helper copy describing recommended ranges to keep renders performant.
 */
@Composable
private fun ExperimentalSection(
    stream: StreamSelection,
    adjustments: AdjustmentSettings,
    onAdjustmentsChange: (StreamSelection, (AdjustmentSettings) -> AdjustmentSettings) -> Unit
) {
    Spacer(modifier = Modifier.height(12.dp))
    AdjustmentSlider(
        label = "Pixellate",
        tooltip = "Creates a retro pixel-art effect by reducing resolution. Higher values create larger pixel blocks.",
        value = adjustments.pixellate,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(pixellate = newValue) } },
        valueRange = 0f..50f,
        steps = 49,
        valueFormatter = { "${it.roundToInt()}" },
        supportingText = "0 = off, 1-10 = subtle, 11-30 = moderate, 31-50 = extreme pixelation.",
        validation = if (adjustments.pixellate in 0f..50f) null else {
            AdjustmentValidation(true, "Pixellate stays between 0 and 50.")
        }
    )
    AdjustmentSlider(
        label = "Color Cycle Speed",
        tooltip = "Rotates hues over time for psychedelic loops. Max speed = 3 full rotations per second!",
        value = adjustments.colorCycleSpeed,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(colorCycleSpeed = newValue) } },
        valueRange = 0f..3f,
        steps = 30,
        valueFormatter = { String.format("%.1f rot/s", it) },
        supportingText = "0 = off, 0.5-1 = subtle shift, 1.5-2 = moderate, 2.5-3 = extreme psychedelic effect.",
        validation = if (adjustments.colorCycleSpeed in 0f..3f) null else {
            AdjustmentValidation(true, "Color cycle speed stays between 0 and 3 rotations/second.")
        }
    )
    AdjustmentSlider(
        label = "Motion Trails",
        tooltip = "Creates intense motion blur by mixing multiple frames together. Extreme values create Bruce Lee-style action trails.",
        value = adjustments.motionTrails,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(motionTrails = newValue) } },
        valueRange = 0f..1f,
        steps = 10,
        supportingText = "Low = subtle ghosting (2 frames), High = extreme motion trails (10 frames).",
        validation = if (adjustments.motionTrails in 0f..1f) null else {
            AdjustmentValidation(true, "Motion Trails stays between 0 and 1.")
        }
    )
    AdjustmentSlider(
        label = "Sharpen",
        tooltip = "Enhances edge contrast for crisper detail.",
        value = adjustments.sharpen,
        onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(sharpen = newValue) } },
        valueRange = 0f..1f,
        steps = 10,
        supportingText = "Use sparingly to avoid halos.",
        validation = if (adjustments.sharpen in 0f..1f) null else {
            AdjustmentValidation(true, "Sharpen stays between 0 and 1.")
        }
    )
    AdjustmentSwitch(
        label = "Edge Detect",
        tooltip = "Converts frames into stylized white-on-black line art.",
        checked = adjustments.edgeDetectEnabled,
        onCheckedChange = { isChecked -> onAdjustmentsChange(stream) { it.copy(edgeDetectEnabled = isChecked) } },
        supportingText = "Shows only detected edges when enabled."
    )
    if (adjustments.edgeDetectEnabled) {
        AdjustmentSlider(
            label = "Edge Threshold",
            tooltip = "Controls edge detection sensitivity. Higher values detect more edges.",
            value = adjustments.edgeDetectThreshold,
            onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(edgeDetectThreshold = newValue) } },
            valueRange = 0f..1f,
            steps = 20,
            supportingText = "Lower = only strong edges, Higher = more edge detail.",
            validation = if (adjustments.edgeDetectThreshold in 0f..1f) null else {
                AdjustmentValidation(true, "Edge Threshold stays between 0 and 1.")
            }
        )
        AdjustmentSlider(
            label = "Edge Boost",
            tooltip = "Enhances brightness and contrast of detected edges.",
            value = adjustments.edgeDetectBoost,
            onValueChange = { newValue -> onAdjustmentsChange(stream) { it.copy(edgeDetectBoost = newValue) } },
            valueRange = 0f..1f,
            steps = 10,
            supportingText = "Makes white edges 'pop' more against the black background.",
            validation = if (adjustments.edgeDetectBoost in 0f..1f) null else {
                AdjustmentValidation(true, "Edge Boost stays between 0 and 1.")
            }
        )
    }
    AdjustmentSwitch(
        label = "Negate Colors",
        tooltip = "Inverts every color channel for a negative-film look.",
        checked = adjustments.negateColors,
        onCheckedChange = { isChecked -> onAdjustmentsChange(stream) { it.copy(negateColors = isChecked) } },
        supportingText = "Pair with color cycling for neon results."
    )
    AdjustmentSwitch(
        label = "Flip Horizontal",
        tooltip = "Mirrors the frame across the vertical axis.",
        checked = adjustments.flipHorizontal,
        onCheckedChange = { isChecked -> onAdjustmentsChange(stream) { it.copy(flipHorizontal = isChecked) } },
        supportingText = "Helpful when reorienting mirrored footage."
    )
    AdjustmentSwitch(
        label = "Flip Vertical",
        tooltip = "Flips the GIF upside down.",
        checked = adjustments.flipVertical,
        onCheckedChange = { isChecked -> onAdjustmentsChange(stream) { it.copy(flipVertical = isChecked) } },
        supportingText = "Combine with horizontal flip for 180° rotation without FFmpeg filters."
    )
}

/**
 * Shows the most recent render output for a specific stream. Falls back to the imported thumbnail
 * while no GIF exists and displays contextual messaging so editors understand why the preview might
 * be empty. Coil drives GIF playback directly inside the card. Each layer has two of these cards -
 * one for Stream A and one for Stream B, both visible simultaneously.
 */
@Composable
private fun StreamPreviewCard(
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
                    .aspectRatio(16f / 9f), // Use 16:9 aspect ratio to match typical video
                contentAlignment = Alignment.Center
            ) {
                when {
                    streamState.generatedGifPath != null -> {
                        val imageRequest = remember(streamState.generatedGifPath) {
                            val gifFile = File(streamState.generatedGifPath)
                            coil.request.ImageRequest.Builder(context)
                                .data(gifFile)
                                .crossfade(true)
                                .apply {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        decoderFactory(coil.decode.ImageDecoderDecoder.Factory())
                                    } else {
                                        decoderFactory(coil.decode.GifDecoder.Factory())
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

/**
 * Aggregates the layer blend controls including mode selection, opacity slider, and action button.
 * Enablement mirrors the readiness of Stream A/B outputs so FFmpeg jobs are never dispatched
 * without prerequisite renders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlendPreviewCard(
    layerState: Layer,
    onBlendModeChange: (GifVisionBlendMode) -> Unit,
    onBlendOpacityChange: (Float) -> Unit,
    onGenerateBlend: () -> Unit
) {
    val streamAReady = !layerState.streamA.generatedGifPath.isNullOrBlank()
    val streamBReady = !layerState.streamB.generatedGifPath.isNullOrBlank()
    val isGenerating = layerState.blendState.isGenerating
    val controlsEnabled = streamAReady && streamBReady && !isGenerating

    ElevatedCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Blend Preview", style = MaterialTheme.typography.titleLarge)

            if (!streamAReady || !streamBReady) {
                Text(
                    text = "Render Stream A and Stream B to unlock blending.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            BlendModeDropdown(
                mode = layerState.blendState.mode,
                enabled = controlsEnabled,
                onModeSelected = onBlendModeChange
            )

            BlendOpacitySlider(
                opacity = layerState.blendState.opacity,
                enabled = controlsEnabled,
                onOpacityChange = onBlendOpacityChange
            )

            GenerateBlendButton(
                enabled = controlsEnabled,
                onGenerate = onGenerateBlend
            )

            if (isGenerating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            BlendPreviewThumbnail(path = layerState.blendState.blendedGifPath)
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0:00.000"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
    val millis = durationMs % 1000
    return String.format("%d:%02d.%03d", minutes, seconds, millis)
}

private fun formatResolution(width: Int?, height: Int?): String? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    return "${width}×${height}"
}