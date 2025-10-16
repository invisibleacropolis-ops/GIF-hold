package com.gifvision.app.ui.components

import com.gifvision.app.ui.layer.layerBlendControlsAvailability
import com.gifvision.app.ui.master.MasterBlendAvailability
import com.gifvision.app.ui.master.masterBlendAvailability
import com.gifvision.app.ui.state.BlendConfig
import com.gifvision.app.ui.state.Layer
import com.gifvision.app.ui.state.MasterBlendConfig
import com.gifvision.app.ui.state.ShareSetupState
import com.gifvision.app.ui.state.Stream
import com.gifvision.app.ui.state.StreamSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class BlendControlsAvailabilityTest {

    @Test
    fun layerAvailability_disablesControlsUntilBothStreamsReady() {
        val baseLayer = Layer(id = 1, title = "Layer 1")

        val initialAvailability = layerBlendControlsAvailability(baseLayer)
        assertEquals(false, initialAvailability.controlsEnabled)
        assertEquals(false, initialAvailability.generateEnabled)
        assertEquals(false, initialAvailability.isGenerating)

        val streamAOnly = baseLayer.copy(
            streamA = baseLayer.streamA.copy(generatedGifPath = "a.gif")
        )
        val streamAAvailability = layerBlendControlsAvailability(streamAOnly)
        assertEquals(false, streamAAvailability.controlsEnabled)
        assertEquals(true, streamAAvailability.generateEnabled)
        assertEquals(false, streamAAvailability.isGenerating)

        val bothStreams = streamAOnly.copy(
            streamB = baseLayer.streamB.copy(generatedGifPath = "b.gif")
        )
        val bothAvailability = layerBlendControlsAvailability(bothStreams)
        assertEquals(true, bothAvailability.controlsEnabled)
        assertEquals(true, bothAvailability.generateEnabled)
        assertEquals(false, bothAvailability.isGenerating)
    }

    @Test
    fun layerAvailability_disablesWhileGenerating() {
        val generatingLayer = Layer(
            id = 1,
            title = "Layer 1",
            streamA = Stream(stream = StreamSelection.A, generatedGifPath = "a.gif"),
            streamB = Stream(stream = StreamSelection.B, generatedGifPath = "b.gif"),
            blendState = BlendConfig(isGenerating = true)
        )

        val availability = layerBlendControlsAvailability(generatingLayer)
        assertEquals(false, availability.controlsEnabled)
        assertEquals(false, availability.generateEnabled)
        assertEquals(true, availability.isGenerating)
    }

    @Test
    fun masterAvailability_tracksControlSaveAndShareStates() {
        val baseState = MasterBlendConfig()

        val initial = masterBlendAvailability(baseState)
        assertMasterAvailability(
            initial,
            expectedControlsEnabled = false,
            expectedGenerateEnabled = false,
            expectedSaveEnabled = false,
            expectedShareEnabled = false,
            expectedLabel = "Generate Master Blend"
        )

        val enabled = baseState.copy(isEnabled = true)
        val enabledAvailability = masterBlendAvailability(enabled)
        assertMasterAvailability(
            enabledAvailability,
            expectedControlsEnabled = true,
            expectedGenerateEnabled = true,
            expectedSaveEnabled = false,
            expectedShareEnabled = false,
            expectedLabel = "Generate Master Blend"
        )

        val withOutput = enabled.copy(masterGifPath = "master.gif")
        val outputAvailability = masterBlendAvailability(withOutput)
        assertMasterAvailability(
            outputAvailability,
            expectedControlsEnabled = true,
            expectedGenerateEnabled = true,
            expectedSaveEnabled = true,
            expectedShareEnabled = true,
            expectedLabel = "Regenerate Master Blend"
        )

        val sharing = withOutput.copy(
            shareSetup = withOutput.shareSetup.copy(isPreparingShare = true)
        )
        val shareAvailability = masterBlendAvailability(sharing)
        assertMasterAvailability(
            shareAvailability,
            expectedControlsEnabled = true,
            expectedGenerateEnabled = true,
            expectedSaveEnabled = true,
            expectedShareEnabled = false,
            expectedLabel = "Regenerate Master Blend"
        )
    }

    @Test
    fun masterAvailability_disablesDuringGeneration() {
        val state = MasterBlendConfig(
            isEnabled = true,
            isGenerating = true,
            masterGifPath = "master.gif",
            shareSetup = ShareSetupState(hashtags = emptyList())
        )

        val availability = masterBlendAvailability(state)
        assertMasterAvailability(
            availability,
            expectedControlsEnabled = false,
            expectedGenerateEnabled = false,
            expectedSaveEnabled = false,
            expectedShareEnabled = false,
            expectedLabel = "Regenerate Master Blend"
        )
    }

    private fun assertMasterAvailability(
        snapshot: MasterBlendAvailability,
        expectedControlsEnabled: Boolean,
        expectedGenerateEnabled: Boolean,
        expectedSaveEnabled: Boolean,
        expectedShareEnabled: Boolean,
        expectedLabel: String
    ) {
        assertEquals(expectedControlsEnabled, snapshot.controls.controlsEnabled)
        assertEquals(expectedGenerateEnabled, snapshot.controls.generateEnabled)
        assertEquals(expectedSaveEnabled, snapshot.saveEnabled)
        assertEquals(expectedShareEnabled, snapshot.shareEnabled)
        assertEquals(expectedLabel, snapshot.generateLabel)
    }
}
