package com.gifvision.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import com.gifvision.app.navigation.GifVisionDestination
import com.gifvision.app.ui.state.AdjustmentSettings
import com.gifvision.app.ui.state.GifVisionBlendMode
import com.gifvision.app.ui.state.GifLoopMetadata
import com.gifvision.app.ui.state.GifVisionUiState
import com.gifvision.app.ui.state.LogSeverity
import com.gifvision.app.ui.state.StreamSelection

/** Simple model for the bottom navigation items. */
private data class BottomNavItem(
    val label: String,
    val route: String,
    val layerId: Int? = null
)

/**
 * Top-level Compose entry point that wires the scaffold, accessibility toggles, bottom
 * navigation, and navigation host together. The composable mirrors the routing structure in
 * [GifVisionDestination] and fans out callbacks so `MainActivity` can keep the state mutations
 * centralized inside `GifVisionViewModel`. The host is window-size aware, enabling responsive
 * layouts when tablets or foldables report a medium/expanded width class.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GifVisionApp(
    modifier: Modifier = Modifier,
    uiState: GifVisionUiState,
    windowSizeClass: WindowSizeClass,
    isHighContrastEnabled: Boolean,
    onSelectLayer: (Int) -> Unit,
    onSelectStream: (layerId: Int, StreamSelection) -> Unit,
    onAdjustmentsChange: (layerId: Int, StreamSelection, (AdjustmentSettings) -> AdjustmentSettings) -> Unit,
    onImportClip: (layerId: Int, Uri) -> Unit,
    onStreamTrimChange: (layerId: Int, StreamSelection, Long, Long) -> Unit,
    onStreamPlaybackChange: (layerId: Int, StreamSelection, Long) -> Unit,
    onStreamPlayPauseChange: (layerId: Int, StreamSelection, Boolean) -> Unit,
    onRequestStreamRender: (layerId: Int, StreamSelection) -> Unit,
    onSaveStreamOutput: (layerId: Int, StreamSelection) -> Unit,
    onLayerBlendModeChange: (layerId: Int, GifVisionBlendMode) -> Unit,
    onLayerBlendOpacityChange: (layerId: Int, Float) -> Unit,
    onRequestLayerBlend: (layerId: Int) -> Unit,
    onMasterBlendModeChange: (GifVisionBlendMode) -> Unit,
    onMasterBlendOpacityChange: (Float) -> Unit,
    onRequestMasterBlend: () -> Unit,
    onSaveMasterBlend: () -> Unit,
    onShareMasterBlend: () -> Unit,
    onShareCaptionChange: (String) -> Unit,
    onShareHashtagsChange: (String) -> Unit,
    onShareLoopMetadataChange: (GifLoopMetadata) -> Unit,
    onAppendLog: (layerId: Int?, String, LogSeverity) -> Unit,
    onToggleHighContrast: () -> Unit,
    onSetHighContrast: (Boolean) -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentLayerId = navBackStackEntry?.arguments?.getInt(GifVisionDestination.Layer.ARG_LAYER_ID)
    val currentRoute = navBackStackEntry?.destination?.route
    val isLargeScreen = windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium

    val firstLayer = uiState.layers.getOrNull(0)
    val secondLayer = uiState.layers.getOrNull(1)
    val bottomItems = buildList {
        firstLayer?.let { layer ->
            add(
                BottomNavItem(
                    label = "Layer 1",
                    route = GifVisionDestination.Layer.createRoute(layer.id),
                    layerId = layer.id
                )
            )
        }
        secondLayer?.let { layer ->
            add(
                BottomNavItem(
                    label = "Layer 2",
                    route = GifVisionDestination.Layer.createRoute(layer.id),
                    layerId = layer.id
                )
            )
        }
        add(BottomNavItem(label = "Master", route = GifVisionDestination.MasterBlend.route, layerId = null))
    }

    // When the active layer in state changes (e.g., user tapped Stream toggle), keep navigation in sync.
    LaunchedEffect(uiState.activeLayerIndex) {
        val expectedLayerId = uiState.layers.getOrNull(uiState.activeLayerIndex)?.id ?: return@LaunchedEffect
        if (currentRoute == GifVisionDestination.MasterBlend.route) return@LaunchedEffect
        if (currentLayerId == expectedLayerId) return@LaunchedEffect

        navController.navigate(GifVisionDestination.Layer.createRoute(expectedLayerId)) {
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = "GifVision", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        IconButton(
                            onClick = onToggleHighContrast,
                            modifier = Modifier
                                .size(40.dp)
                                .semantics {
                                    contentDescription = if (isHighContrastEnabled) {
                                        "Toggle high contrast off"
                                    } else {
                                        "Toggle high contrast on"
                                    }
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.InvertColors,
                                contentDescription = null,
                                tint = if (isHighContrastEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Text(
                            text = "High contrast",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = isHighContrastEnabled,
                            onCheckedChange = { onSetHighContrast(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.54f)
                            ),
                            modifier = Modifier.semantics {
                                contentDescription = if (isHighContrastEnabled) {
                                    "High contrast enabled"
                                } else {
                                    "High contrast disabled"
                                }
                            }
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                bottomItems.forEach { item ->
                    val isLayerItem = item.layerId != null
                    val selected = if (isLayerItem) {
                        currentLayerId == item.layerId
                    } else {
                        currentRoute == GifVisionDestination.MasterBlend.route
                    }
                    val enabled = if (isLayerItem) {
                        true
                    } else {
                        uiState.masterBlend.isEnabled || currentRoute == GifVisionDestination.MasterBlend.route
                    }

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (isLayerItem) {
                                val index = uiState.layers.indexOfFirst { it.id == item.layerId }
                                if (index >= 0) onSelectLayer(index)
                            }
                            navController.navigate(item.route) {
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Text(
                                text = item.label.first().toString(),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { contentDescription = item.label }
                            )
                        },
                        label = { Text(item.label) },
                        enabled = enabled
                    )
                }
            }
        }
    ) { innerPadding ->
        val startLayerId = uiState.layers.firstOrNull()?.id ?: 1
        NavHost(
            navController = navController,
            startDestination = GifVisionDestination.Layer.createRoute(startLayerId),
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(
                route = GifVisionDestination.Layer.route,
                arguments = listOf(navArgument(GifVisionDestination.Layer.ARG_LAYER_ID) { type = NavType.IntType })
            ) { entry ->
                val layerId = entry.arguments?.getInt(GifVisionDestination.Layer.ARG_LAYER_ID)
                val layer = uiState.layers.firstOrNull { it.id == layerId }
                if (layer == null) {
                    MissingLayerState()
                } else {
                    LaunchedEffect(layer.id) {
                        val index = uiState.layers.indexOf(layer)
                        if (index >= 0 && uiState.activeLayerIndex != index) {
                            onSelectLayer(index)
                        }
                    }
                    LayerScreen(
                        layerState = layer,
                        isWideLayout = isLargeScreen,
                        onStreamSelected = { stream -> onSelectStream(layer.id, stream) },
                        onAdjustmentsChange = { stream, transformer ->
                            onAdjustmentsChange(layer.id, stream, transformer)
                        },
                        onImportClip = { uri -> onImportClip(layer.id, uri) },
                        onTrimRangeChange = { stream, start, end ->
                            onStreamTrimChange(layer.id, stream, start, end)
                        },
                        onPlaybackPositionChange = { stream, position ->
                            onStreamPlaybackChange(layer.id, stream, position)
                        },
                        onPlayPauseChange = { stream, playing ->
                            onStreamPlayPauseChange(layer.id, stream, playing)
                        },
                        onRequestStreamRender = { stream -> onRequestStreamRender(layer.id, stream) },
                        onSaveStreamOutput = { stream -> onSaveStreamOutput(layer.id, stream) },
                        onBlendModeChange = { mode -> onLayerBlendModeChange(layer.id, mode) },
                        onBlendOpacityChange = { opacity -> onLayerBlendOpacityChange(layer.id, opacity) },
                        onGenerateBlend = { onRequestLayerBlend(layer.id) },
                        onAppendLog = { message, severity -> onAppendLog(layer.id, message, severity) }
                    )
                }
            }

            composable(GifVisionDestination.MasterBlend.route) {
                MasterBlendScreen(
                    state = uiState.masterBlend,
                    isWideLayout = isLargeScreen,
                    onModeChange = onMasterBlendModeChange,
                    onOpacityChange = onMasterBlendOpacityChange,
                    onGenerateMasterBlend = onRequestMasterBlend,
                    onSaveMasterBlend = onSaveMasterBlend,
                    onShareMasterBlend = onShareMasterBlend,
                    onShareCaptionChange = onShareCaptionChange,
                    onShareHashtagsChange = onShareHashtagsChange,
                    onShareLoopMetadataChange = onShareLoopMetadataChange
                )
            }
        }
    }
}

/**
 * Fallback surface rendered when navigation arguments reference a layer id that no longer exists
 * in state (for example, if deep links restore a stale task). Presenting an explicit message keeps
 * the scaffold usable while signalling that the caller should re-import clips.
 */
@Composable
private fun MissingLayerState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Layer not available",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
    }
}
