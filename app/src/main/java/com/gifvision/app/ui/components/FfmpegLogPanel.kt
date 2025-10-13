package com.gifvision.app.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gifvision.app.ui.state.LogEntry
import com.gifvision.app.ui.state.LogSeverity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reusable FFmpeg diagnostic panel. The panel stays pinned on screen, auto-scrolls as new entries
 * stream in, and exposes copy/share affordances so support teams can gather diagnostics quickly.
 */
@Suppress("DEPRECATION") // Using deprecated LocalClipboardManager until new API is stable
@Composable
fun FfmpegLogPanel(
    title: String,
    logs: List<LogEntry>,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val formattedPayload = remember(logs) { logs.joinToString(separator = "\n") { it.toDisplayString() } }
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = {
                            if (logs.isEmpty()) {
                                Toast.makeText(context, "No logs to copy yet", Toast.LENGTH_SHORT).show()
                            } else {
                                clipboard.setText(AnnotatedString(formattedPayload))
                                Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = "Copy FFmpeg logs" }
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null)
                    }
                    IconButton(
                        onClick = {
                            if (logs.isEmpty()) {
                                Toast.makeText(context, "Generate activity before sharing logs", Toast.LENGTH_SHORT).show()
                            } else {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, formattedPayload)
                                    putExtra(Intent.EXTRA_SUBJECT, "GifVision FFmpeg diagnostics")
                                }
                                runCatching {
                                    val chooser = Intent.createChooser(intent, "Share FFmpeg logs")
                                    context.startActivity(chooser)
                                }.onFailure {
                                    Toast.makeText(context, "No compatible app found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = "Share FFmpeg logs" }
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null)
                    }
                }
            }

            HorizontalDivider()

            if (logs.isEmpty()) {
                Text(
                    text = "No log output yet. Start a render to capture FFmpeg diagnostics.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logs.size) { index ->
                        val entry = logs[index]
                        LogLine(entry = entry)
                    }
                }
            }
        }
    }
}

/**
 * Single log row that renders timestamp, severity accent, and message payload. The styling mirrors
 * common terminal output conventions so engineers can skim warnings and errors quickly.
 */
@Composable
private fun LogLine(entry: LogEntry) {
    val color = when (entry.severity) {
        LogSeverity.Info -> MaterialTheme.colorScheme.onSurfaceVariant
        LogSeverity.Warning -> MaterialTheme.colorScheme.tertiary
        LogSeverity.Error -> MaterialTheme.colorScheme.error
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .heightIn(min = 24.dp)
                .background(color = color, shape = MaterialTheme.shapes.extraSmall)
        )
        Column {
            Text(
                text = entry.timestampMillis.toTimeString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = color
            )
        }
    }
}

private fun LogEntry.toDisplayString(): String {
    return "[${timestampMillis.toTimeString()}] ${severity.name}: $message"
}

private fun Long.toTimeString(): String {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(this))
}
