package com.gifvision.app.ui.layout

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Describes common padding and spacing metrics consumed by GifVision surfaces. Centralizing these
 * values keeps the layer and master screens visually aligned while making it trivial to adjust
 * breakpoints or spacing policies in one location.
 */
data class LayoutMetrics(
    val contentPaddingHorizontal: Dp,
    val contentPaddingVertical: Dp,
    val sectionSpacing: Dp,
    val columnSpacing: Dp
) {
    companion object {
        /** Default spacing tuned for phone and tablet layouts. */
        val Default = LayoutMetrics(
            contentPaddingHorizontal = 16.dp,
            contentPaddingVertical = 24.dp,
            sectionSpacing = 20.dp,
            columnSpacing = 20.dp
        )
    }
}

/**
 * Derives the active [LayoutMetrics] from the computed [UiLayoutConfig]. The current policy uses a
 * single baseline for all width classes, but exposing the hook allows future phases to provide
 * tablet-specific spacing without rewriting individual screens.
 */
fun layoutMetricsFor(config: UiLayoutConfig): LayoutMetrics {
    // For Phase 1 the spacing is identical regardless of width classification; the helper exists
    // so later sessions can branch on [config.isWideLayout] without touching multiple call sites.
    return LayoutMetrics.Default
}

/** Convenience overload that derives a [UiLayoutConfig] before returning [LayoutMetrics]. */
fun layoutMetricsFor(windowSizeClass: WindowSizeClass): LayoutMetrics {
    return layoutMetricsFor(computeUiLayoutConfig(windowSizeClass))
}
