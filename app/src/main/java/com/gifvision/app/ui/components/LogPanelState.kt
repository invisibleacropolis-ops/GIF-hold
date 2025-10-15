package com.gifvision.app.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.gifvision.app.ui.state.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * State holder for [FfmpegLogPanel] that centralises auto-scroll behaviour and clipboard/share
 * actions so screens can reuse the same wiring. Screens may hoist the state if they need to
 * coordinate diagnostics across multiple surfaces.
 */
@Stable
class LogPanelState internal constructor(
    private val clipboardManager: ClipboardManager,
    private val context: Context,
    val listState: LazyListState
) {
    /** Indicates whether any log entries have been recorded. */
    var hasLogs: Boolean by mutableStateOf(false)
        private set

    /** Cached string representation of the current log buffer for copy/share operations. */
    var formattedPayload: String by mutableStateOf("")
        private set

    internal fun refresh(logs: List<LogEntry>) {
        hasLogs = logs.isNotEmpty()
        formattedPayload = if (logs.isEmpty()) {
            ""
        } else {
            logs.joinToString(separator = "\n") { it.toDisplayString() }
        }
    }

    fun copyLogs(
        emptyMessage: String = DEFAULT_COPY_EMPTY_MESSAGE,
        successMessage: String = DEFAULT_COPY_SUCCESS_MESSAGE
    ) {
        if (!hasLogs) {
            showToast(emptyMessage)
            return
        }
        clipboardManager.setText(AnnotatedString(formattedPayload))
        showToast(successMessage)
    }

    fun shareLogs(
        emptyMessage: String = DEFAULT_SHARE_EMPTY_MESSAGE,
        chooserTitle: String = DEFAULT_SHARE_CHOOSER_TITLE,
        subject: String = DEFAULT_SHARE_SUBJECT,
        noHandlerMessage: String = DEFAULT_SHARE_NO_HANDLER_MESSAGE
    ) {
        if (!hasLogs) {
            showToast(emptyMessage)
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, formattedPayload)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        runCatching {
            val chooser = Intent.createChooser(intent, chooserTitle)
            context.startActivity(chooser)
        }.onFailure {
            showToast(noHandlerMessage)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val DEFAULT_COPY_EMPTY_MESSAGE: String = "No logs to copy yet"
        const val DEFAULT_COPY_SUCCESS_MESSAGE: String = "Logs copied"
        const val DEFAULT_SHARE_EMPTY_MESSAGE: String = "Generate activity before sharing logs"
        const val DEFAULT_SHARE_CHOOSER_TITLE: String = "Share FFmpeg logs"
        const val DEFAULT_SHARE_SUBJECT: String = "GifVision FFmpeg diagnostics"
        const val DEFAULT_SHARE_NO_HANDLER_MESSAGE: String = "No compatible app found"
    }
}

@Suppress("DEPRECATION") // Using deprecated LocalClipboardManager until new API is stable
@Composable
fun rememberLogPanelState(logs: List<LogEntry>): LogPanelState {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val state = remember(clipboardManager, context, listState) {
        LogPanelState(
            clipboardManager = clipboardManager,
            context = context,
            listState = listState
        )
    }

    state.refresh(logs)

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            state.listState.animateScrollToItem(logs.lastIndex)
        }
    }

    return state
}

internal fun LogEntry.toDisplayString(): String {
    return "[${timestampMillis.toLogTimestamp()}] ${severity.name}: $message"
}

internal fun Long.toLogTimestamp(): String {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(this))
}
