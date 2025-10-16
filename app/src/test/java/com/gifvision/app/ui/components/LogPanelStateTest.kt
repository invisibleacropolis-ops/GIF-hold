package com.gifvision.app.ui.components

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
        val clipboard = RecordingClipboardManager()
        val state = LogPanelState(
            clipboardManager = clipboard,
            context = SilentContext(),
            listState = LazyListState()
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

    private class RecordingClipboardManager : ClipboardManager {
        var recordedText: AnnotatedString? = null

        override fun setText(annotatedString: AnnotatedString) {
            recordedText = annotatedString
        }

        override fun getText(): AnnotatedString? = recordedText
    }

    private class SilentContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
