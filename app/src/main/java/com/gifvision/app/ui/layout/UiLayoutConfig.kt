package com.gifvision.app.ui.layout

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass

/**
 * Lightweight value object that captures layout decisions derived from the host window metrics.
 *
 * Phase 1 of the modularization plan focuses on file extraction without changing behaviour; this
 * helper simply mirrors the existing `GifVisionApp` breakpoint check so future sessions can expand
 * it with additional policies (column counts, padding, etc.) without searching through the
 * navigation scaffold.
 */
data class UiLayoutConfig(
    val isWideLayout: Boolean
)

/**
 * Compute the current [UiLayoutConfig] using the same breakpoint heuristic that previously lived in
 * `GifVisionApp`. Keeping the logic in one place avoids duplicating the width classification check
 * once other surfaces need to react to layout changes.
 */
fun computeUiLayoutConfig(windowSizeClass: WindowSizeClass): UiLayoutConfig {
    val isWideLayout = windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium
    return UiLayoutConfig(isWideLayout = isWideLayout)
}
