package com.gifvision.app.ui.resources

/**
 * Shared strings for layer-specific surfaces. Consolidating this copy avoids drift between the
 * layer screen cards and keeps future localization work centralized.
 */
object LayerCopy {
    const val STREAM_OUTPUTS_TITLE: String = "Stream Outputs"
    const val BLEND_PREVIEW_TITLE: String = "Blend Preview"
    const val BLEND_REQUIREMENT_HINT: String = "Render Stream A or Stream B to unlock blending."

    fun singleStreamReady(streamLabel: String): String =
        "$streamLabel is ready. Generating a blend will reuse it without combining streams."
}
