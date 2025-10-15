package com.gifvision.app.ui.state.coordinators

import android.app.Application
import android.net.Uri
import com.gifvision.app.media.GifProcessingCoordinator
import com.gifvision.app.media.GifProcessingEvent
import com.gifvision.app.media.InMemoryMediaRepository
import com.gifvision.app.media.LayerBlendRequest
import com.gifvision.app.media.MasterBlendRequest
import com.gifvision.app.media.StreamProcessingRequest
import com.gifvision.app.ui.state.AdjustmentSettings
import com.gifvision.app.ui.state.LogSeverity
import com.gifvision.app.ui.state.Stream
import com.gifvision.app.ui.state.StreamSelection
import com.gifvision.app.ui.state.messages.MessageCenter
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RenderSchedulerTest {

    private val tempDir: File = Files.createTempDirectory("render-scheduler-test").toFile()

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `cancelling stream render logs warning and clears job`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = object : GifProcessingCoordinator {
            override fun renderStream(request: StreamProcessingRequest): Flow<GifProcessingEvent> = callbackFlow {
                trySend(GifProcessingEvent.Started(request.jobId))
                awaitClose { }
            }

            override fun blendLayer(request: LayerBlendRequest): Flow<GifProcessingEvent> = flowOf()

            override fun mergeMaster(request: MasterBlendRequest): Flow<GifProcessingEvent> = flowOf()
        }

        val messageCenter = MessageCenter()
        val scheduler = RenderScheduler(
            application = Application(),
            processingCoordinator = coordinator,
            mediaRepository = InMemoryMediaRepository(),
            messageCenter = messageCenter,
            ioDispatcher = dispatcher,
            timestampProvider = { 0L },
            cacheDirectoryProvider = { tempDir },
            inputStreamProvider = { ByteArrayInputStream("data".toByteArray()) }
        )

        val generatingStates = mutableListOf<Boolean>()
        val loggedMessages = mutableListOf<Pair<String, LogSeverity>>()

        scheduler.requestStreamRender(
            scope = this,
            layerId = 5,
            streamSelection = StreamSelection.A,
            streamState = Stream(stream = StreamSelection.A, adjustments = AdjustmentSettings()),
            sourceUri = Uri.parse("file:///fake.mp4"),
            onGeneratingChange = { generatingStates += it },
            onStreamCompleted = { _, _ -> error("Should not complete when cancelled") },
            onLog = { message, severity -> loggedMessages += message to severity }
        )

        advanceUntilIdle()
        assertTrue(scheduler.isStreamJobActive(5, StreamSelection.A))

        val cancelled = scheduler.cancelStreamRender(5, StreamSelection.A)
        assertTrue(cancelled)

        advanceUntilIdle()

        assertFalse(scheduler.isStreamJobActive(5, StreamSelection.A))
        assertEquals(listOf(true, false), generatingStates)
        assertTrue(loggedMessages.any { (message, severity) ->
            message.contains("cancelled") && severity == LogSeverity.Warning
        })

    }

    @Test
    fun `stream completion stores output and clears job`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = object : GifProcessingCoordinator {
            override fun renderStream(request: StreamProcessingRequest): Flow<GifProcessingEvent> = flowOf(
                GifProcessingEvent.Started(request.jobId),
                GifProcessingEvent.Progress(request.jobId, 0.5f, "Halfway there"),
                GifProcessingEvent.Completed(
                    jobId = request.jobId,
                    outputPath = request.suggestedOutputPath ?: "${request.jobId}_${UUID.randomUUID()}.gif",
                    logs = listOf("Finished render")
                )
            )

            override fun blendLayer(request: LayerBlendRequest): Flow<GifProcessingEvent> = flowOf()

            override fun mergeMaster(request: MasterBlendRequest): Flow<GifProcessingEvent> = flowOf()
        }

        val messageCenter = MessageCenter()
        val repository = InMemoryMediaRepository()
        val scheduler = RenderScheduler(
            application = Application(),
            processingCoordinator = coordinator,
            mediaRepository = repository,
            messageCenter = messageCenter,
            ioDispatcher = dispatcher,
            timestampProvider = { 42L },
            cacheDirectoryProvider = { tempDir },
            inputStreamProvider = { ByteArrayInputStream("data".toByteArray()) }
        )

        val generatingStates = mutableListOf<Boolean>()
        val completed = mutableListOf<String>()
        val loggedMessages = mutableListOf<String>()

        scheduler.requestStreamRender(
            scope = this,
            layerId = 2,
            streamSelection = StreamSelection.B,
            streamState = Stream(stream = StreamSelection.B, adjustments = AdjustmentSettings()),
            sourceUri = Uri.parse("file:///fake.mp4"),
            onGeneratingChange = { generatingStates += it },
            onStreamCompleted = { path, _ -> completed += path },
            onLog = { message, _ -> loggedMessages += message }
        )

        advanceUntilIdle()

        assertFalse(scheduler.isStreamJobActive(2, StreamSelection.B))
        assertEquals(listOf(true, false), generatingStates)
        assertEquals(1, completed.size)
        assertTrue(completed.first().endsWith(".gif"))
        assertTrue(loggedMessages.any { it.contains("complete") })

    }
}
