package com.gifvision.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.gifvision.app.media.FfmpegKitGifProcessingCoordinator
import com.gifvision.app.ui.GifVisionApp
import com.gifvision.app.ui.state.GifVisionViewModel
import com.gifvision.app.ui.theme.GifVisionTheme

class MainActivity : ComponentActivity() {
    private val viewModel: GifVisionViewModel by viewModels {
        GifVisionViewModel.Factory
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Verify FFmpeg drawtext filter support on app startup
        // This will log detailed diagnostics and catch configuration issues early
        val drawtextSupported = FfmpegKitGifProcessingCoordinator.verifyDrawtextSupport()
        if (!drawtextSupported) {
            android.util.Log.e("MainActivity", "WARNING: Text overlay feature may not work correctly!")
        }
        
        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(activity = this)
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            GifVisionTheme(highContrast = uiState.isHighContrastEnabled) {
                val context = LocalContext.current
                LaunchedEffect(Unit) {
                    viewModel.uiMessages.collect { message ->
                        Toast.makeText(
                            context,
                            message.message,
                            if (message.isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                GifVisionApp(
                    modifier = Modifier.fillMaxSize(),
                    uiState = uiState,
                    windowSizeClass = windowSizeClass,
                    isHighContrastEnabled = uiState.isHighContrastEnabled,
                    onSelectLayer = viewModel::selectLayer,
                    onSelectStream = viewModel::selectStream,
                    onAdjustmentsChange = viewModel::updateAdjustments,
                    onImportClip = viewModel::importSourceClip,
                    onStreamTrimChange = viewModel::updateStreamTrim,
                    onStreamPlaybackChange = viewModel::updateStreamPlaybackPosition,
                    onStreamPlayPauseChange = viewModel::setStreamPlaying,
                    onRequestStreamRender = viewModel::requestStreamRender,
                    onSaveStreamOutput = viewModel::saveStreamOutput,
                    onLayerBlendModeChange = viewModel::updateLayerBlendMode,
                    onLayerBlendOpacityChange = viewModel::updateLayerBlendOpacity,
                    onRequestLayerBlend = viewModel::requestLayerBlend,
                    onMasterBlendModeChange = viewModel::updateMasterBlendMode,
                    onMasterBlendOpacityChange = viewModel::updateMasterBlendOpacity,
                    onRequestMasterBlend = viewModel::requestMasterBlend,
                    onSaveMasterBlend = viewModel::saveMasterOutput,
                    onShareMasterBlend = viewModel::shareMasterOutput,
                    onShareCaptionChange = viewModel::updateShareCaption,
                    onShareHashtagsChange = viewModel::updateShareHashtags,
                    onShareLoopMetadataChange = viewModel::updateShareLoopMetadata,
                    onAppendLog = viewModel::appendLog,
                    onToggleHighContrast = viewModel::toggleHighContrast,
                    onSetHighContrast = viewModel::setHighContrast
                )
            }
        }
    }
}