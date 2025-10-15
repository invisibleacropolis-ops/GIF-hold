package com.gifvision.app.ui.resources

/**
 * Centralized UI copy for panel titles and preview empty states. Keeping these strings together
 * ensures both layer and master surfaces remain in sync as additional features are introduced.
 */
object PanelCopy {
    /** Title displayed above the FFmpeg log panel on layer screens. */
    fun layerLogTitle(layerTitle: String): String = "$layerTitle FFmpeg Logs"

    /** Title used by the master FFmpeg log panel. */
    const val MASTER_LOG_TITLE: String = "Master FFmpeg Logs"

    /** Header label for the master preview card. */
    const val MASTER_PREVIEW_TITLE: String = "Master Preview"

    /** Empty state prompt shown when no blended GIF has been produced yet. */
    const val BLEND_PREVIEW_EMPTY_STATE: String = "No blended GIF yet"

    /** Empty state prompt encouraging editors to render the final master blend. */
    const val MASTER_PREVIEW_EMPTY_STATE: String = "Generate Master Blend"
}
