package com.gifvision.app.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gifvision.app.ui.components.LogPanelState
import com.gifvision.app.ui.components.rememberLogPanelState
import com.gifvision.app.ui.components.toLogTimestamp
import com.gifvision.app.ui.state.LogEntry
import com.gifvision.app.ui.state.LogSeverity

/**
 * Reusable FFmpeg diagnostic panel. The panel stays pinned on screen, auto-scrolls as new entries
 * stream in, and exposes copy/share affordances so support teams can gather diagnostics quickly.
 */
@Composable
fun FfmpegLogPanel(
    title: String,
    logs: List<LogEntry>,
    modifier: Modifier = Modifier,
    state: LogPanelState = rememberLogPanelState(logs)
) {
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
                        onClick = { state.copyLogs() },
                        modifier = Modifier.semantics { contentDescription = "Copy FFmpeg logs" }
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null)
                    }
                    IconButton(
                        onClick = { state.shareLogs() },
                        modifier = Modifier.semantics { contentDescription = "Share FFmpeg logs" }
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null)
                    }
                }
            }

            HorizontalDivider()

            if (!state.hasLogs) {
                Text(
                    text = "No log output yet. Start a render to capture FFmpeg diagnostics.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    state = state.listState,
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
                text = entry.timestampMillis.toLogTimestamp(),
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
