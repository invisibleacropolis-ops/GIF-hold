package com.gifvision.app.ui.master

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.gifvision.app.ui.components.FfmpegLogPanel
import com.gifvision.app.ui.state.GifLoopMetadata
import com.gifvision.app.ui.state.GifVisionBlendMode
import com.gifvision.app.ui.state.MasterBlendConfig

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

    val masterControls: @Composable () -> Unit = {
        MasterControlsCard(
            state = state,
            onModeChange = onModeChange,
            onOpacityChange = onOpacityChange,
            onGenerateMasterBlend = onGenerateMasterBlend,
            onSaveMasterBlend = onSaveMasterBlend,
            onShareMasterBlend = onShareMasterBlend
        )
    }

    val previewCard: @Composable () -> Unit = {
        MasterPreviewCard(state = state)
    }

    val shareSetupCard: @Composable () -> Unit = {
        MasterShareSetupCard(
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
