package com.gifvision.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gifvision.app.ui.components.BlendControlsCard
import com.gifvision.app.ui.components.GifPreviewCard
import com.gifvision.app.ui.components.FfmpegLogPanel
import com.gifvision.app.ui.components.rememberFfmpegLogPanelState
import com.gifvision.app.ui.components.ShareSetupCard
import com.gifvision.app.ui.state.GifVisionBlendMode
import com.gifvision.app.ui.state.MasterBlendConfig
import com.gifvision.app.ui.state.GifLoopMetadata

/**
 * Screen responsible for combining the outputs from Layer 1 and Layer 2, configuring the final
 * blend, and preparing share collateral. The composable adapts its layout based on the available
 * width so controls, previews, and diagnostics remain visible on larger devices while still
 * stacking vertically on phones. All callbacks are hoisted to `GifVisionViewModel` so rendering and
 * persistence stay centralized.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val saveEnabled = state.masterGifPath != null && !state.isGenerating
    val shareEnabled = state.masterGifPath != null && !state.isGenerating && !state.shareSetup.isPreparingShare
    val generateLabel = if (state.masterGifPath == null) "Generate Master Blend" else "Regenerate Master Blend"
    val masterOpacitySupportingText = "0.00 shows only Layer 1 Blend · 1.00 fully overlays Layer 2 Blend"
    val masterStatusMessage = when {
        !state.isEnabled -> "Generate blends for Layer 1 and Layer 2 to unlock the master controls."
        state.isGenerating -> "Master blend rendering in progress…"
        else -> null
    }

    val logPanelState = rememberFfmpegLogPanelState()

    val masterControls: @Composable () -> Unit = {
        BlendControlsCard(
            title = "Master Blend",
            mode = state.mode,
            opacity = state.opacity,
            onModeSelected = onModeChange,
            onOpacityChange = onOpacityChange,
            onGenerateBlend = onGenerateMasterBlend,
            controlsEnabled = controlsEnabled,
            generateEnabled = controlsEnabled,
            isGenerating = state.isGenerating,
            statusMessage = masterStatusMessage,
            sliderSupportingText = masterOpacitySupportingText,
            generateLabel = generateLabel,
            actionContent = {
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
        )
    }

    val previewCard: @Composable () -> Unit = {
        GifPreviewCard(
            title = "Master Preview",
            previewPath = state.masterGifPath,
            emptyStateText = "Generate Master Blend"
        )
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
            modifier = Modifier.fillMaxWidth(),
            state = logPanelState
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
 * Social sharing card lives in `ui/components` now so additional surfaces can reuse previews and
 * metadata controls without re-implementing Material styling or helper messaging.
 */

