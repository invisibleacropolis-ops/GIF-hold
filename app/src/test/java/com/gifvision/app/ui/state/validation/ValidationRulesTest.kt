package com.gifvision.app.ui.state.validation

import com.gifvision.app.ui.state.AdjustmentSettings
import com.gifvision.app.ui.state.GifVisionBlendMode
import com.gifvision.app.ui.state.GifVisionUiState
import com.gifvision.app.ui.state.Layer
import com.gifvision.app.ui.state.MasterBlendConfig
import com.gifvision.app.ui.state.Stream
import com.gifvision.app.ui.state.StreamSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationRulesTest {

    @Test
    fun validateStream_reportsAllBlockingReasons() {
        val layer = Layer(id = 1, title = "Layer 1")
        val stream = Stream(
            stream = StreamSelection.A,
            adjustments = AdjustmentSettings(
                resolutionPercent = 0f,
                frameRate = 0f,
                maxColors = 999,
                clipDurationSeconds = 0f
            )
        )

        val result = validateStream(layer, stream)

        assertTrue(result is StreamValidation.Error)
        val reasons = (result as StreamValidation.Error).reasons
        assertEquals(5, reasons.size)
        assertTrue(reasons.any { it.contains("select a source clip") })
        assertTrue(reasons.any { it.contains("Resolution must") })
        assertTrue(reasons.any { it.contains("Frame rate") })
        assertTrue(reasons.any { it.contains("Color palette") })
        assertTrue(reasons.any { it.contains("Clip duration") })
    }

    @Test
    fun validateLayerBlend_readyWhenStreamsRenderedAndModeSupported() {
        val layer = Layer(
            id = 1,
            title = "Layer 1",
            streamA = Stream(stream = StreamSelection.A, generatedGifPath = "a.gif"),
            streamB = Stream(stream = StreamSelection.B, generatedGifPath = "b.gif"),
            blendState = com.gifvision.app.ui.state.BlendConfig(
                mode = GifVisionBlendMode.Normal,
                opacity = 0.5f
            )
        )

        val result = validateLayerBlend(layer)

        assertTrue(result is LayerBlendValidation.Ready)
    }

    @Test
    fun validateLayerBlend_blocksUnsupportedNegateCombination() {
        val layer = Layer(
            id = 1,
            title = "Layer 1",
            streamA = Stream(
                stream = StreamSelection.A,
                generatedGifPath = "a.gif",
                adjustments = AdjustmentSettings(negateColors = true)
            ),
            streamB = Stream(stream = StreamSelection.B, generatedGifPath = "b.gif"),
            blendState = com.gifvision.app.ui.state.BlendConfig(
                mode = GifVisionBlendMode.Color,
                opacity = 0.5f
            )
        )

        val result = validateLayerBlend(layer)

        assertTrue(result is LayerBlendValidation.Blocked)
        val reasons = (result as LayerBlendValidation.Blocked).reasons
        assertEquals(1, reasons.size)
        assertTrue(reasons.first().contains("incompatible"))
    }

    @Test
    fun validateMasterBlend_requiresBothLayerBlends() {
        val state = GifVisionUiState(
            layers = listOf(
                Layer(id = 1, title = "Layer 1", blendState = com.gifvision.app.ui.state.BlendConfig(blendedGifPath = null)),
                Layer(id = 2, title = "Layer 2", blendState = com.gifvision.app.ui.state.BlendConfig(blendedGifPath = "layer2.gif"))
            ),
            masterBlend = MasterBlendConfig(opacity = 0.4f)
        )

        val result = validateMasterBlend(state)

        assertTrue(result is MasterBlendValidation.Blocked)
        val reasons = (result as MasterBlendValidation.Blocked).reasons
        assertTrue(reasons.any { it.contains("Blend each layer") })
    }

    @Test
    fun validateMasterBlend_readyWhenAllInputsPresent() {
        val state = GifVisionUiState(
            layers = listOf(
                Layer(
                    id = 1,
                    title = "Layer 1",
                    blendState = com.gifvision.app.ui.state.BlendConfig(
                        blendedGifPath = "layer1.gif",
                        mode = GifVisionBlendMode.Normal
                    )
                ),
                Layer(
                    id = 2,
                    title = "Layer 2",
                    blendState = com.gifvision.app.ui.state.BlendConfig(
                        blendedGifPath = "layer2.gif",
                        mode = GifVisionBlendMode.Multiply
                    )
                )
            ),
            masterBlend = MasterBlendConfig(
                mode = GifVisionBlendMode.Addition,
                opacity = 0.8f
            )
        )

        val result = validateMasterBlend(state)

        assertTrue(result is MasterBlendValidation.Ready)
    }
}
