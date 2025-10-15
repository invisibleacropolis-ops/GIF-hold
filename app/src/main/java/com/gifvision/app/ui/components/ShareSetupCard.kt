package com.gifvision.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gifvision.app.ui.state.GifLoopMetadata
import com.gifvision.app.ui.state.PlatformPreview
import com.gifvision.app.ui.state.ShareSetupState
import kotlin.math.abs

/**
 * Shared card that gathers social sharing metadata (caption, hashtags, loop descriptors) and shows
 * live previews for each supported platform. The state flow is hoisted so updates can recompute
 * previews inside the view-model, keeping the UI stateless beyond the provided callbacks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareSetupCard(
    shareSetup: ShareSetupState,
    onCaptionChange: (String) -> Unit,
    onHashtagsChange: (String) -> Unit,
    onLoopMetadataChange: (GifLoopMetadata) -> Unit,
    modifier: Modifier = Modifier,
    headline: String = "Social Share Setup"
) {
    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = headline, style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = shareSetup.caption,
                onValueChange = onCaptionChange,
                label = { Text("Caption") },
                placeholder = { Text("Tell the story behind your loop…") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            OutlinedTextField(
                value = shareSetup.hashtagsInput,
                onValueChange = onHashtagsChange,
                label = { Text("Hashtags") },
                placeholder = { Text("#gifvision #loopmagic") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    val helper = if (shareSetup.hashtags.isEmpty()) {
                        "Separate hashtags with spaces or commas."
                    } else {
                        "Parsed: ${shareSetup.hashtags.joinToString(" ")}".trim()
                    }
                    Text(
                        text = helper,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            if (shareSetup.isPreparingShare) {
                Text(
                    text = "Preparing share intent…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(text = "Loop Metadata", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GifLoopMetadata.entries.forEach { metadata ->
                    FilterChip(
                        selected = shareSetup.loopMetadata == metadata,
                        onClick = { onLoopMetadataChange(metadata) },
                        label = { Text(metadata.displayName) }
                    )
                }
            }

            HorizontalDivider()
            Text(text = "Platform Previews", style = MaterialTheme.typography.titleMedium)
            shareSetup.platformPreviews.forEach { preview ->
                PlatformPreviewCard(preview = preview)
            }
        }
    }
}

/**
 * Renders a read-only card representing how the pending caption/hashtag payload will appear on a
 * specific destination (e.g., Instagram or X). The component highlights truncation limits so teams
 * can tune copy without leaving the app.
 */
@Composable
fun PlatformPreviewCard(
    preview: PlatformPreview,
    modifier: Modifier = Modifier,
    headline: String = preview.platform.displayName
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = headline, style = MaterialTheme.typography.titleMedium)
            Text(
                text = preview.loopMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            if (preview.renderedCaption.isBlank()) {
                Text(
                    text = "Caption preview appears once you add copy or hashtags.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(preview.renderedCaption, style = MaterialTheme.typography.bodyMedium)
                if (preview.isCaptionTruncated) {
                    Text(
                        text = "Preview truncated to ${preview.platform.captionLimit} characters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            val remaining = preview.remainingCharacters
            val characterMessage = if (remaining >= 0) {
                "$remaining characters remaining"
            } else {
                "${abs(remaining)} characters over limit"
            }
            Text(
                text = characterMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (remaining >= 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )

            val hashtagMessage = if (preview.overflowHashtags > 0) {
                "${preview.hashtagCount}/${preview.platform.hashtagLimit} hashtags (${preview.overflowHashtags} over limit)"
            } else {
                "${preview.hashtagCount}/${preview.platform.hashtagLimit} hashtags"
            }
            Text(
                text = hashtagMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (preview.overflowHashtags > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
