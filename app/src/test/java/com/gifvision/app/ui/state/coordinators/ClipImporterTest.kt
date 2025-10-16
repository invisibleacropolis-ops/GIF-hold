package com.gifvision.app.ui.state.coordinators

import android.app.Application
import android.net.Uri
import com.gifvision.app.media.MediaAsset
import com.gifvision.app.media.MediaRepository
import com.gifvision.app.media.MediaSource
import com.gifvision.app.media.StreamSelection
import com.gifvision.app.ui.state.AdjustmentSettings
import com.gifvision.app.ui.state.BlendConfig
import com.gifvision.app.ui.state.Layer
import com.gifvision.app.ui.state.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ClipImporterTest {

    @Test
    fun resetLayer_clearsStreamsAndBlendState() {
        val importer = ClipImporter(
            application = Application(),
            mediaRepository = NoOpMediaRepository
        )
        val layer = Layer(
            id = 7,
            title = "Layer 7",
            streamA = Stream(
                stream = StreamSelection.A,
                adjustments = AdjustmentSettings(
                    resolutionPercent = 0.4f,
                    frameRate = 48f,
                    clipDurationSeconds = 12f
                ),
                sourcePreviewPath = "content://preview/a",
                generatedGifPath = "/tmp/a.gif",
                isGenerating = true,
                lastRenderTimestamp = 123L,
                playbackPositionMs = 456L,
                isPlaying = true,
                trimStartMs = 100L,
                trimEndMs = 999L
            ),
            streamB = Stream(
                stream = StreamSelection.B,
                adjustments = AdjustmentSettings(
                    resolutionPercent = 0.9f,
                    frameRate = 15f,
                    clipDurationSeconds = 28f
                ),
                sourcePreviewPath = "content://preview/b",
                generatedGifPath = "/tmp/b.gif",
                isGenerating = true,
                lastRenderTimestamp = 321L,
                playbackPositionMs = 654L,
                isPlaying = true,
                trimStartMs = 50L,
                trimEndMs = 1_500L
            ),
            blendState = BlendConfig(
                blendedGifPath = "/tmp/layer7.gif",
                isGenerating = true
            )
        )

        val reset = importer.resetLayer(layer)

        assertNull(reset.sourceClip)
        assertEquals(AdjustmentSettings(), reset.streamA.adjustments)
        assertEquals(AdjustmentSettings(), reset.streamB.adjustments)
        assertNull(reset.streamA.sourcePreviewPath)
        assertNull(reset.streamB.sourcePreviewPath)
        assertNull(reset.streamA.generatedGifPath)
        assertNull(reset.streamB.generatedGifPath)
        assertFalse(reset.streamA.isGenerating)
        assertFalse(reset.streamB.isGenerating)
        assertNull(reset.streamA.lastRenderTimestamp)
        assertNull(reset.streamB.lastRenderTimestamp)
        assertEquals(0L, reset.streamA.playbackPositionMs)
        assertEquals(0L, reset.streamB.playbackPositionMs)
        assertFalse(reset.streamA.isPlaying)
        assertFalse(reset.streamB.isPlaying)
        assertEquals(0L, reset.streamA.trimStartMs)
        assertEquals(0L, reset.streamB.trimStartMs)
        assertEquals(0L, reset.streamA.trimEndMs)
        assertEquals(0L, reset.streamB.trimEndMs)
        assertNull(reset.streamA.previewThumbnail)
        assertNull(reset.streamB.previewThumbnail)
        assertNull(reset.blendState.blendedGifPath)
        assertFalse(reset.blendState.isGenerating)
    }

    private object NoOpMediaRepository : MediaRepository {
        override suspend fun registerSourceClip(
            layerId: Int,
            uri: Uri,
            displayName: String?
        ): MediaSource = throw UnsupportedOperationException()

        override suspend fun storeStreamOutput(
            layerId: Int,
            stream: StreamSelection,
            suggestedPath: String?
        ): MediaAsset = throw UnsupportedOperationException()

        override suspend fun storeLayerBlend(layerId: Int, suggestedPath: String?): MediaAsset =
            throw UnsupportedOperationException()

        override suspend fun storeMasterBlend(suggestedPath: String?): MediaAsset =
            throw UnsupportedOperationException()

        override suspend fun getSourceClip(layerId: Int): MediaSource? = throw UnsupportedOperationException()

        override suspend fun exportToDownloads(sourcePath: String, displayName: String): Uri =
            throw UnsupportedOperationException()
    }
}
