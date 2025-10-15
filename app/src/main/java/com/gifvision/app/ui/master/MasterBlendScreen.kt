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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.gifvision.app.ui.components.FfmpegLogPanel
import com.gifvision.app.ui.components.rememberLogPanelState
import com.gifvision.app.ui.layout.LayoutMetrics
import com.gifvision.app.ui.resources.PanelCopy
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
    layoutMetrics: LayoutMetrics = LayoutMetrics.Default,
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
    val logPanelState = rememberLogPanelState(logs = state.ffmpegLogs)

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
            title = PanelCopy.MASTER_LOG_TITLE,
            logs = state.ffmpegLogs,
            modifier = Modifier.fillMaxWidth(),
            state = logPanelState
        )
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
                    masterControls()
                    logPanel()
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(layoutMetrics.sectionSpacing)
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
