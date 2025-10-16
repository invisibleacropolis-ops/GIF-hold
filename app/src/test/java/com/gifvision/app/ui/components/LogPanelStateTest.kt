package com.gifvision.app.ui.components

import com.gifvision.app.ui.state.LogEntry
import com.gifvision.app.ui.state.LogSeverity
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LogPanelStateTest {

    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun captureEnvironment() {
        originalLocale = Locale.getDefault()
        originalTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreEnvironment() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun refresh_tracksBufferAndFormatsPayload() {
        val sideEffects = RecordingLogPanelSideEffects()
        val state = LogPanelState(
            listState = androidx.compose.foundation.lazy.LazyListState(),
            sideEffects = sideEffects
        )

        state.refresh(emptyList())
        assertFalse(state.hasLogs)
        assertEquals("", state.formattedPayload)

        val logs = listOf(
            LogEntry(message = "Stream render queued", severity = LogSeverity.Info, timestampMillis = 1_000L),
            LogEntry(message = "Blend failed", severity = LogSeverity.Error, timestampMillis = 61_000L)
        )

        state.refresh(logs)

        assertTrue(state.hasLogs)
        assertEquals("[00:00:01] Info: Stream render queued\n[00:01:01] Error: Blend failed", state.formattedPayload)
    }

    @Test
    fun toDisplayString_formatsSeverityWithTimestamp() {
        val entry = LogEntry(
            message = "Render complete",
            severity = LogSeverity.Warning,
            timestampMillis = 3_661_000L
        )

        val display = entry.toDisplayString()

        assertEquals("[01:01:01] Warning: Render complete", display)
    }

    @Test
    fun toLogTimestamp_formats24HourClock() {
        val timestamp = 86_399_000L // 23:59:59

        assertEquals("23:59:59", timestamp.toLogTimestamp())
    }

    @Test
    fun copyLogs_withoutPayload_notifiesEmptyToast() {
        val sideEffects = RecordingLogPanelSideEffects()
        val state = LogPanelState(
            listState = androidx.compose.foundation.lazy.LazyListState(),
            sideEffects = sideEffects
        )

        state.copyLogs(emptyMessage = "Nothing")

        assertEquals(listOf("Nothing"), sideEffects.recordedToasts)
        assertTrue(sideEffects.copiedPayloads.isEmpty())
    }

    @Test
    fun copyLogs_withPayload_copiesAndShowsSuccess() {
        val sideEffects = RecordingLogPanelSideEffects()
        val state = LogPanelState(
            listState = androidx.compose.foundation.lazy.LazyListState(),
            sideEffects = sideEffects
        )

        val logs = listOf(
            LogEntry(message = "Stream render queued", severity = LogSeverity.Info, timestampMillis = 1_000L)
        )
        state.refresh(logs)

        state.copyLogs(successMessage = "Copied")

        assertEquals(listOf("Copied"), sideEffects.recordedToasts)
        assertEquals(listOf("[00:00:01] Info: Stream render queued"), sideEffects.copiedPayloads)
    }

    @Test
    fun shareLogs_withoutPayload_notifiesEmptyToast() {
        val sideEffects = RecordingLogPanelSideEffects()
        val state = LogPanelState(
            listState = androidx.compose.foundation.lazy.LazyListState(),
            sideEffects = sideEffects
        )

        state.shareLogs(emptyMessage = "Empty")

        assertEquals(listOf("Empty"), sideEffects.recordedToasts)
        assertTrue(sideEffects.shareRequests.isEmpty())
    }

    @Test
    fun shareLogs_withPayload_invokesShare() {
        val sideEffects = RecordingLogPanelSideEffects()
        val state = LogPanelState(
            listState = androidx.compose.foundation.lazy.LazyListState(),
            sideEffects = sideEffects
        )

        val logs = listOf(
            LogEntry(message = "Blend finished", severity = LogSeverity.Info, timestampMillis = 2_000L)
        )
        state.refresh(logs)

        state.shareLogs(chooserTitle = "Chooser", subject = "Subject")

        assertTrue(sideEffects.recordedToasts.isEmpty())
        assertEquals(
            listOf(
                RecordingLogPanelSideEffects.ShareRequest(
                    payload = "[00:00:02] Info: Blend finished",
                    chooserTitle = "Chooser",
                    subject = "Subject"
                )
            ),
            sideEffects.shareRequests
        )
    }

    @Test
    fun shareLogs_failure_showsNoHandlerToast() {
        val sideEffects = RecordingLogPanelSideEffects().apply {
            shareResult = Result.failure(IllegalStateException("no handler"))
        }
        val state = LogPanelState(
            listState = androidx.compose.foundation.lazy.LazyListState(),
            sideEffects = sideEffects
        )

        val logs = listOf(
            LogEntry(message = "Blend finished", severity = LogSeverity.Info, timestampMillis = 2_000L)
        )
        state.refresh(logs)

        state.shareLogs(noHandlerMessage = "Missing")

        assertEquals(listOf("Missing"), sideEffects.recordedToasts)
    }

    private class RecordingLogPanelSideEffects : LogPanelSideEffects {
        val copiedPayloads = mutableListOf<String>()
        val shareRequests = mutableListOf<ShareRequest>()
        val recordedToasts = mutableListOf<String>()
        var shareResult: Result<Unit> = Result.success(Unit)

        override fun copyToClipboard(payload: String) {
            copiedPayloads += payload
        }

        override fun sharePayload(
            payload: String,
            chooserTitle: String,
            subject: String
        ): Result<Unit> {
            shareRequests += ShareRequest(payload, chooserTitle, subject)
            return shareResult
        }

        override fun showToast(message: String) {
            recordedToasts += message
        }

        data class ShareRequest(
            val payload: String,
            val chooserTitle: String,
            val subject: String
        )
    }
}
