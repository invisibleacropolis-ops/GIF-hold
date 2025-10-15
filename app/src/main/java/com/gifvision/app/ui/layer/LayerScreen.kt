package com.gifvision.app.ui.layer

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gifvision.app.ui.components.FfmpegLogPanel
import com.gifvision.app.ui.components.rememberLogPanelState
import com.gifvision.app.ui.layout.LayoutMetrics
import com.gifvision.app.ui.state.AdjustmentSettings
import com.gifvision.app.ui.state.GifVisionBlendMode
import com.gifvision.app.ui.state.Layer
import com.gifvision.app.ui.state.LogSeverity
import com.gifvision.app.ui.state.StreamSelection
import com.gifvision.app.ui.resources.LayerCopy
import com.gifvision.app.ui.resources.LogCopy
import com.gifvision.app.ui.resources.PanelCopy
import com.gifvision.app.ui.resources.VIDEO_MIME_TYPES

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
    layoutMetrics: LayoutMetrics = LayoutMetrics.Default,
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
    val logPanelState = rememberLogPanelState(logs = layerState.ffmpegLogs)

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
                    LogCopy.persistablePermissionDenied(security.message),
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
            .padding(
                horizontal = layoutMetrics.contentPaddingHorizontal,
                vertical = layoutMetrics.contentPaddingVertical
            ),
        verticalArrangement = Arrangement.spacedBy(layoutMetrics.sectionSpacing)
    ) {
        if (isWideLayout) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(layoutMetrics.columnSpacing),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(layoutMetrics.sectionSpacing)
                ) {
                    UploadCard(
                        layerState = layerState,
                        onBrowseForClip = {
                            openDocumentLauncher.launch(VIDEO_MIME_TYPES)
                        },
                        onAppendLog = onAppendLog
                    )
                    VideoPreviewCard(
                        layerState = layerState,
                        streamState = activeStreamState,
                        onTrimChange = { start, end ->
                            onTrimRangeChange(layerState.activeStream, start, end)
                        },
                        onPlaybackChange = { position ->
                            onPlaybackPositionChange(layerState.activeStream, position)
                        },
                        onPlayPauseChange = { isPlaying ->
                            onPlayPauseChange(layerState.activeStream, isPlaying)
                        }
                    )
                    AdjustmentsCard(
                        layerState = layerState,
                        onStreamSelected = onStreamSelected,
                        onAdjustmentsChange = onAdjustmentsChange
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(layoutMetrics.sectionSpacing)
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
                        title = PanelCopy.layerLogTitle(layerState.title),
                        logs = layerState.ffmpegLogs,
                        modifier = Modifier.fillMaxWidth(),
                        state = logPanelState
                    )
                }
            }
        } else {
            UploadCard(
                layerState = layerState,
                onBrowseForClip = {
                    openDocumentLauncher.launch(VIDEO_MIME_TYPES)
                },
                onAppendLog = onAppendLog
            )

            VideoPreviewCard(
                layerState = layerState,
                streamState = activeStreamState,
                onTrimChange = { start, end ->
                    onTrimRangeChange(layerState.activeStream, start, end)
                },
                onPlaybackChange = { position ->
                    onPlaybackPositionChange(layerState.activeStream, position)
                },
                onPlayPauseChange = { isPlaying ->
                    onPlayPauseChange(layerState.activeStream, isPlaying)
                }
            )

            AdjustmentsCard(
                layerState = layerState,
                onStreamSelected = onStreamSelected,
                onAdjustmentsChange = onAdjustmentsChange
            )

            Text(
                text = LayerCopy.STREAM_OUTPUTS_TITLE,
                style = MaterialTheme.typography.titleMedium
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
                title = PanelCopy.layerLogTitle(layerState.title),
                logs = layerState.ffmpegLogs,
                modifier = Modifier.fillMaxWidth(),
                state = logPanelState
            )
        }
    }
}
