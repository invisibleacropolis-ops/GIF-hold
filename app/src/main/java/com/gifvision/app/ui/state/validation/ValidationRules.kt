package com.gifvision.app.ui.state.validation

import com.gifvision.app.ui.state.GifVisionBlendMode
import com.gifvision.app.ui.state.GifVisionUiState
import com.gifvision.app.ui.state.Layer
import com.gifvision.app.ui.state.Stream

/** Applies validation rules to a single stream render request. */
fun validateStream(layer: Layer, stream: Stream): StreamValidation {
    val errors = mutableListOf<String>()
    if (layer.sourceClip?.uri == null) {
        errors += "${layer.title}: select a source clip before rendering"
    }
    if (stream.adjustments.resolutionPercent <= 0f) {
        errors += "Resolution must be greater than 0%"
    }
    if (stream.adjustments.frameRate <= 0f) {
        errors += "Frame rate must be positive"
    }
    if (stream.adjustments.maxColors !in 2..256) {
        errors += "Color palette must be between 2 and 256 colors"
    }
    if (stream.adjustments.clipDurationSeconds <= 0f) {
        errors += "Clip duration must be positive"
    }
    return if (errors.isEmpty()) StreamValidation.Valid else StreamValidation.Error(errors)
}

/** Applies validation rules to the layer blend card. */
fun validateLayerBlend(layer: Layer): LayerBlendValidation {
    val errors = mutableListOf<String>()
    val streamAReady = !layer.streamA.generatedGifPath.isNullOrBlank()
    val streamBReady = !layer.streamB.generatedGifPath.isNullOrBlank()
    if (!streamAReady && !streamBReady) {
        errors += "Render Stream A or Stream B before blending"
    }
    if (layer.blendState.opacity !in 0f..1f) {
        errors += "Layer opacity must remain between 0 and 1"
    }
    detectUnsupportedLayerBlend(layer)?.let { errors += it }
    return if (errors.isEmpty()) LayerBlendValidation.Ready else LayerBlendValidation.Blocked(errors)
}

/** Applies validation rules to the master blend surface. */
fun validateMasterBlend(state: GifVisionUiState): MasterBlendValidation {
    val reasons = mutableListOf<String>()
    if (state.layers.any { it.blendState.blendedGifPath.isNullOrBlank() }) {
        reasons += "Blend each layer before attempting the master mix"
    }
    if (state.masterBlend.opacity !in 0f..1f) {
        reasons += "Master blend opacity must remain between 0 and 1"
    }
    detectUnsupportedMasterBlend(state)?.let { reasons += it }
    return if (reasons.isEmpty()) MasterBlendValidation.Ready else MasterBlendValidation.Blocked(reasons)
}

fun detectUnsupportedLayerBlend(layer: Layer): String? {
    val colorSensitiveModes = setOf(
        GifVisionBlendMode.Color,
        GifVisionBlendMode.Hue,
        GifVisionBlendMode.Saturation,
        GifVisionBlendMode.Luminosity
    )
    val negateEnabled = layer.streamA.adjustments.negateColors || layer.streamB.adjustments.negateColors
    return if (layer.blendState.mode in colorSensitiveModes && negateEnabled) {
        "${layer.title}: ${layer.blendState.mode.displayName} is incompatible with the Negate Colors filter"
    } else {
        null
    }
}

fun detectUnsupportedMasterBlend(state: GifVisionUiState): String? {
    val layerUsesDifference = state.layers.any { it.blendState.mode == GifVisionBlendMode.Difference }
    return if (layerUsesDifference && state.masterBlend.mode == GifVisionBlendMode.ColorDodge) {
        "Master blend Color Dodge cannot combine with Difference layer blends"
    } else {
        null
    }
}
