package com.gifvision.app.media

import com.gifvision.app.ui.state.GifVisionBlendMode
import com.gifvision.app.ui.state.StreamSelection
import java.util.UUID

/**
 * Central source of truth for FFmpeg job identifiers. Consolidating job ID construction ensures
 * future features generate predictable, descriptive identifiers without duplicating string
 * templates.
 */
object RenderJobRegistry {
    /** Returns the job ID used when rendering a specific stream within a layer. */
    fun streamRenderId(layerId: Int, stream: StreamSelection): String {
        return "layer-${layerId}-stream-${stream.name.lowercase()}-render"
    }

    /** Returns the job ID used when blending Stream A and B inside a layer. */
    fun layerBlendId(layerId: Int, blendMode: GifVisionBlendMode): String {
        return "layer-${layerId}-blend-${blendMode.name.lowercase()}"
    }

    /** Returns the job ID used when creating the final master blend output. */
    fun masterBlendId(blendMode: GifVisionBlendMode, token: UUID = UUID.randomUUID()): String {
        return "master-blend-${blendMode.name.lowercase()}-${token}"
    }
}
