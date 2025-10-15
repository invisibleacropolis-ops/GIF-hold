package com.gifvision.app.ui.state.messages

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class MessageCenterTest {

    @Test
    fun `deduplicates messages within configured window`() = runTest {
        val clock = AtomicLong(0L)
        val sharedFlow = MutableSharedFlow<UiMessage>(extraBufferCapacity = 0)
        val messageCenter = MessageCenter(
            MessageCenter.Config(
                clock = { clock.get() },
                dedupeWindowMillis = 1_000L,
                sharedFlowFactory = { sharedFlow }
            )
        )

        val collected = mutableListOf<UiMessage>()
        val collectJob = launch { messageCenter.messages.collect { collected += it } }

        messageCenter.post(this, "Render completed")
        advanceUntilIdle()

        // Second emission with identical payload should be suppressed while clock unchanged.
        messageCenter.post(this, "Render completed")
        advanceUntilIdle()

        // Advance the logical clock beyond dedupe window to allow message through.
        clock.set(1_500L)
        messageCenter.post(this, "Render completed")
        advanceUntilIdle()

        collectJob.cancel()

        assertEquals(2, collected.size)
        assertTrue(collected.all { it.message == "Render completed" && !it.isError })
    }

    @Test
    fun `ignores blank messages while still trimming content`() = runTest {
        val sharedFlow = MutableSharedFlow<UiMessage>(extraBufferCapacity = 0)
        val messageCenter = MessageCenter(
            MessageCenter.Config(
                sharedFlowFactory = { sharedFlow }
            )
        )

        val collected = mutableListOf<UiMessage>()
        val collectJob = launch { messageCenter.messages.collect { collected += it } }

        messageCenter.post(this, "   ")
        advanceUntilIdle()

        messageCenter.post(this, "  Warning issued  ", isError = true)
        advanceUntilIdle()

        collectJob.cancel()

        assertEquals(1, collected.size)
        assertEquals("Warning issued", collected.first().message)
        assertTrue(collected.first().isError)
    }
}
