package com.gifvision.app.ui.layer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.gifvision.app.ui.state.Layer
import com.gifvision.app.ui.state.Stream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Embeds an ExoPlayer instance that mirrors the selected stream's trim and playback state. The
 * composable owns the preview transport controls, syncs playback position back to the view-model,
 * and clamps scrubber/trim ranges so FFmpeg always receives consistent start/end times.
 */
@Composable
internal fun VideoPreviewCard(
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

internal fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0:00.000"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
    val millis = durationMs % 1000
    return String.format("%d:%02d.%03d", minutes, seconds, millis)
}
