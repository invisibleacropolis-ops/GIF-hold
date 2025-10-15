package com.gifvision.app.ui.state.coordinators

import android.net.Uri
import com.gifvision.app.media.MediaAsset
import com.gifvision.app.media.MediaRepository
import com.gifvision.app.media.MediaSource
import com.gifvision.app.media.ShareRepository
import com.gifvision.app.media.ShareRequest
import com.gifvision.app.media.ShareResult
import com.gifvision.app.ui.state.Layer
import com.gifvision.app.ui.state.MasterBlendConfig
import com.gifvision.app.ui.state.ShareSetupState
import com.gifvision.app.ui.state.Stream
import com.gifvision.app.ui.state.StreamSelection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCoordinatorTest {

    @Test
    fun `share master blend propagates repository success`() = runBlocking {
        val shareRequests = mutableListOf<ShareRequest>()
        val coordinator = ShareCoordinator(
            mediaRepository = RecordingMediaRepository(),
            shareRepository = object : ShareRepository {
                override suspend fun shareAsset(request: ShareRequest): ShareResult {
                    shareRequests += request
                    return ShareResult.Success("Shared ${request.displayName}")
                }
            }
        )

        val master = MasterBlendConfig(
            masterGifPath = "/storage/master.gif",
            shareSetup = ShareSetupState(
                caption = "Look at this",
                hashtags = listOf("#gifvision")
            )
        )

        val result = coordinator.shareMasterBlend(master)

        assertEquals("Shared gifvision_master", result.userMessage)
        assertFalse(result.isError)
        assertEquals(1, shareRequests.size)
        assertEquals("gifvision_master", shareRequests.first().displayName)
    }

    @Test
    fun `share master blend reports missing path`() = runBlocking {
        val coordinator = ShareCoordinator(
            mediaRepository = RecordingMediaRepository(),
            shareRepository = object : ShareRepository {
                override suspend fun shareAsset(request: ShareRequest): ShareResult {
                    return ShareResult.Success("unused")
                }
            }
        )

        val result = coordinator.shareMasterBlend(MasterBlendConfig(masterGifPath = null))

        assertTrue(result.isError)
        assertEquals("Master blend path missing", result.logMessage)
    }

    @Test
    fun `save stream exports using sanitized display name`() = runBlocking {
        val recordingRepository = RecordingMediaRepository()
        val coordinator = ShareCoordinator(
            mediaRepository = recordingRepository,
            shareRepository = object : ShareRepository {
                override suspend fun shareAsset(request: ShareRequest): ShareResult {
                    return ShareResult.Success("unused")
                }
            }
        )
        val layer = Layer(
            id = 3,
            title = "Layer !@# 3",
            streamA = Stream(
                stream = StreamSelection.A,
                generatedGifPath = "/tmp/output_a.gif"
            )
        )

        val result = coordinator.saveStream(layer, StreamSelection.A)

        assertFalse(result.isError)
        assertEquals("Saved Stream A GIF to Downloads", result.userMessage)
        assertEquals("/tmp/output_a.gif", recordingRepository.lastExport?.first)
        assertEquals("layer___3_stream_a", recordingRepository.lastExport?.second)
    }

    @Test
    fun `save master blend reports export failures`() = runBlocking {
        val recordingRepository = RecordingMediaRepository().apply {
            exportFailure = IllegalStateException("disk full")
        }
        val coordinator = ShareCoordinator(
            mediaRepository = recordingRepository,
            shareRepository = object : ShareRepository {
                override suspend fun shareAsset(request: ShareRequest): ShareResult {
                    return ShareResult.Success("unused")
                }
            }
        )
        val master = MasterBlendConfig(masterGifPath = "/tmp/master.gif")

        val result = coordinator.saveMasterBlend(master)

        assertTrue(result.isError)
        assertEquals("disk full", result.userMessage)
    }

    private class RecordingMediaRepository : MediaRepository {
        var lastExport: Pair<String, String>? = null
        var exportFailure: Throwable? = null

        override suspend fun exportToDownloads(sourcePath: String, displayName: String): Uri {
            lastExport = sourcePath to displayName
            exportFailure?.let { throw it }
            return Uri.parse("file:///downloads/${displayName}.gif")
        }

        override suspend fun registerSourceClip(layerId: Int, uri: Uri, displayName: String?): MediaSource {
            error("Not used in tests")
        }

        override suspend fun storeStreamOutput(layerId: Int, stream: StreamSelection, suggestedPath: String?): MediaAsset {
            error("Not used in tests")
        }

        override suspend fun storeLayerBlend(layerId: Int, suggestedPath: String?): MediaAsset {
            error("Not used in tests")
        }

        override suspend fun storeMasterBlend(suggestedPath: String?): MediaAsset {
            error("Not used in tests")
        }

        override suspend fun getSourceClip(layerId: Int): MediaSource? {
            error("Not used in tests")
        }
    }
}
