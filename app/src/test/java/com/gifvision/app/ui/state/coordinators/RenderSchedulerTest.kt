package com.gifvision.app.ui.state.coordinators

import android.app.Application
import android.net.Uri
import com.gifvision.app.media.GifProcessingCoordinator
import com.gifvision.app.media.GifProcessingEvent
import com.gifvision.app.media.LayerBlendRequest
import com.gifvision.app.media.MasterBlendRequest
import com.gifvision.app.media.MediaAsset
import com.gifvision.app.media.MediaRepository
import com.gifvision.app.media.MediaSource
import com.gifvision.app.media.RenderJobRegistry
import com.gifvision.app.media.StreamProcessingRequest
import com.gifvision.app.ui.state.AdjustmentSettings
import com.gifvision.app.ui.state.LogSeverity
import com.gifvision.app.ui.state.Stream
import com.gifvision.app.ui.state.StreamSelection
import com.gifvision.app.ui.state.messages.MessageCenter
import com.gifvision.app.ui.state.messages.UiMessage
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderSchedulerTest {

    @Test
    fun requestStreamRender_copiesInputAndPersistsAsset() = runTest {
        val cacheDir = createTempDir(prefix = "renderSchedulerTest")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val application = object : Application() {
            override fun getCacheDir(): File = cacheDir
        }
        val coordinator = RecordingProcessingCoordinator()
        val repository = RecordingMediaRepository()
        val messagesFlow = MutableSharedFlow<UiMessage>(extraBufferCapacity = 2)
        val messageCenter = MessageCenter(
            config = MessageCenter.Config(
                clock = { 0L },
                dedupeWindowMillis = 0L,
                sharedFlowFactory = { messagesFlow }
            )
        )
        val scheduler = RenderScheduler(
            application = application,
            processingCoordinator = coordinator,
            mediaRepository = repository,
            messageCenter = messageCenter,
            ioDispatcher = dispatcher,
            timestampProvider = { 7L },
            cacheDirectoryProvider = { cacheDir },
            inputStreamProvider = { _: Uri -> ByteArrayInputStream("clip-bytes".toByteArray()) }
        )

        val generatingStates = mutableListOf<Boolean>()
        val logs = mutableListOf<Pair<String, LogSeverity>>()
        var completion: Pair<String, Long>? = null
        val emittedMessages = mutableListOf<UiMessage>()
        val collector = backgroundScope.launch { messageCenter.messages.collect { emittedMessages += it } }

        scheduler.requestStreamRender(
            scope = backgroundScope,
            layerId = 3,
            streamSelection = StreamSelection.A,
            streamState = Stream(
                stream = StreamSelection.A,
                adjustments = AdjustmentSettings(frameRate = 24f),
                trimEndMs = 1_000L
            ),
            sourceUri = Uri.parse("content://gifvision/clip.mp4"),
            onGeneratingChange = { generatingStates += it },
            onStreamCompleted = { path, recordedAt -> completion = path to recordedAt },
            onLog = { message, severity -> logs += message to severity }
        )

        advanceUntilIdle()
        collector.cancel()

        assertEquals(RenderJobRegistry.streamRenderId(3, StreamSelection.A), coordinator.lastStreamRequest?.jobId)
        assertEquals(listOf(true, false), generatingStates)
        assertTrue(logs.any { it.first.contains("progress") })
        assertTrue(logs.any { it.first.contains("complete") })
        assertEquals("stored/path.gif", completion?.first)
        assertEquals(1234L, completion?.second)
        assertTrue(repository.storeStreamCalled)
        assertTrue(scheduler.activeJobSnapshot().isEmpty())
        assertTrue(cacheDir.listFiles()?.none { it.name.startsWith("temp_input") } == true)
        assertTrue(emittedMessages.isEmpty())
    }

    private class RecordingProcessingCoordinator : GifProcessingCoordinator {
        var lastStreamRequest: StreamProcessingRequest? = null

        override fun renderStream(request: StreamProcessingRequest): Flow<GifProcessingEvent> {
            lastStreamRequest = request
            val jobId = request.jobId
            return flow {
                emit(GifProcessingEvent.Started(jobId, "Started $jobId"))
                emit(GifProcessingEvent.Progress(jobId, 0.5f))
                emit(
                    GifProcessingEvent.Completed(
                        jobId = jobId,
                        outputPath = "output/$jobId.gif",
                        logs = listOf("Completed $jobId")
                    )
                )
            }
        }

        override fun blendLayer(request: LayerBlendRequest): Flow<GifProcessingEvent> {
            error("blendLayer should not be invoked in this test")
        }

        override fun mergeMaster(request: MasterBlendRequest): Flow<GifProcessingEvent> {
            error("mergeMaster should not be invoked in this test")
        }
    }

    private class RecordingMediaRepository : MediaRepository {
        var storeStreamCalled = false

        override suspend fun registerSourceClip(layerId: Int, uri: Uri, displayName: String?): MediaSource {
            error("registerSourceClip should not be invoked in this test")
        }

        override suspend fun storeStreamOutput(
            layerId: Int,
            stream: StreamSelection,
            suggestedPath: String?
        ): MediaAsset {
            storeStreamCalled = true
            return MediaAsset(
                layerId = layerId,
                stream = stream,
                path = "stored/path.gif",
                displayName = "Layer $layerId",
                recordedAtEpochMillis = 1234L
            )
        }

        override suspend fun storeLayerBlend(layerId: Int, suggestedPath: String?): MediaAsset {
            error("storeLayerBlend should not be invoked in this test")
        }

        override suspend fun storeMasterBlend(suggestedPath: String?): MediaAsset {
            error("storeMasterBlend should not be invoked in this test")
        }

        override suspend fun getSourceClip(layerId: Int): MediaSource? {
            return null
        }

        override suspend fun exportToDownloads(sourcePath: String, displayName: String): Uri {
            error("exportToDownloads should not be invoked in this test")
        }
    }
}
