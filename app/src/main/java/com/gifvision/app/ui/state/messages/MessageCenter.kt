package com.gifvision.app.ui.state.messages

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Lightweight value object describing a snackbar/toast style message for the activity layer. */
data class UiMessage(val message: String, val isError: Boolean = false)

/**
 * Centralizes message dispatch so the view-model can emit UI messages without duplicating the
 * buffering + coroutine launch boilerplate. The helper exposes a [SharedFlow] so Compose surfaces
 * can collect the feed while keeping message creation thread-safe.
 *
 * The helper also deduplicates identical messages within a short rolling window so repeated
 * emissions (e.g. validation errors from rapid taps) do not overwhelm the user or produce
 * redundant snackbars.
 */
class MessageCenter(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val dedupeWindowMillis: Long = 1_500L
) {
    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 1)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    @Volatile
    private var lastMessage: UiMessage? = null

    @Volatile
    private var lastEmissionTimestamp: Long = 0L

    fun post(scope: CoroutineScope, message: String, isError: Boolean = false) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return
        val payload = UiMessage(trimmed, isError)

        val shouldEmit = synchronized(this) {
            val now = clock()
            val recentDuplicate = lastMessage == payload && (now - lastEmissionTimestamp) < dedupeWindowMillis
            if (recentDuplicate) {
                false
            } else {
                lastMessage = payload
                lastEmissionTimestamp = now
                true
            }
        }
        if (!shouldEmit) return

        if (!_messages.tryEmit(payload)) {
            scope.launch { _messages.emit(payload) }
        }
    }
}
