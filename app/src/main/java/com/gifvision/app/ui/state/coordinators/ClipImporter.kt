package com.gifvision.app.ui.state.coordinators

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.asImageBitmap
import com.gifvision.app.media.MediaRepository
import com.gifvision.app.ui.resources.LogCopy
import com.gifvision.app.ui.state.AdjustmentSettings
import com.gifvision.app.ui.state.Layer
import com.gifvision.app.ui.state.LogSeverity
import com.gifvision.app.ui.state.SourceClip
import com.gifvision.app.ui.state.Stream
import kotlin.math.roundToInt

/**
 * Handles the heavy lifting of importing a source clip: metadata extraction, repository
 * registration, thumbnail harvesting, and default adjustment normalization.
 */
internal class ClipImporter(
    private val application: Application,
    private val mediaRepository: MediaRepository
) {

    suspend fun import(layer: Layer, uri: Uri): ClipImportOutcome {
        val metadata = try {
            loadClipMetadata(uri)
        } catch (security: SecurityException) {
            return ClipImportOutcome.Failure(
                message = LogCopy.accessRevoked(uri, security.message),
                severity = LogSeverity.Error,
                resetLayer = true
            )
        } catch (throwable: Throwable) {
            return ClipImportOutcome.Failure(
                message = "Failed to inspect clip: ${throwable.message ?: throwable.javaClass.simpleName}",
                severity = LogSeverity.Error
            )
        }

        val registered = try {
            mediaRepository.registerSourceClip(layer.id, uri, metadata.displayName)
        } catch (security: SecurityException) {
            return ClipImportOutcome.Failure(
                message = LogCopy.storagePermissionRevoked(uri, security.message),
                severity = LogSeverity.Error,
                resetLayer = true
            )
        }

        val clipState = SourceClip(
            uri = uri,
            displayName = metadata.displayName ?: registered.displayName,
            mimeType = metadata.mimeType,
            sizeBytes = metadata.sizeBytes,
            durationMs = metadata.durationMs,
            width = metadata.width,
            height = metadata.height,
            lastModified = metadata.lastModified,
            thumbnail = metadata.thumbnailBitmap?.asImageBitmap()
        )

        val (streamA, warningsA) = adjustStreamDefaults(layer.streamA, clipState)
        val (streamB, warningsB) = adjustStreamDefaults(layer.streamB, clipState)
        val warnings = (warningsA + warningsB).distinct()

        val updatedLayer = layer.copy(
            sourceClip = clipState,
            streamA = streamA,
            streamB = streamB
        )
        return ClipImportOutcome.Success(
            layer = updatedLayer,
            warnings = warnings,
            importedName = clipState.displayName
        )
    }

    fun resetLayer(layer: Layer): Layer {
        return layer.copy(
            sourceClip = null,
            streamA = resetStream(layer.streamA),
            streamB = resetStream(layer.streamB),
            blendState = layer.blendState.copy(
                blendedGifPath = null,
                isGenerating = false
            )
        )
    }

    private fun resetStream(stream: Stream): Stream = stream.copy(
        adjustments = AdjustmentSettings(),
        sourcePreviewPath = null,
        generatedGifPath = null,
        isGenerating = false,
        lastRenderTimestamp = null,
        playbackPositionMs = 0L,
        isPlaying = false,
        trimStartMs = 0L,
        trimEndMs = 0L,
        previewThumbnail = null
    )

    private fun adjustStreamDefaults(stream: Stream, clip: SourceClip): Pair<Stream, List<String>> {
        val durationMs = clip.durationMs ?: 0L
        val sanitizedDuration = durationMs.coerceAtLeast(0L)
        val (adjusted, warnings) = adjustForClipDefaults(stream.adjustments, clip)
        val normalizedAdjustments = adjusted.copy(
            clipDurationSeconds = (sanitizedDuration / 1000f)
                .coerceAtLeast(0.5f)
                .coerceAtMost(30f)
        )
        val updatedStream = stream.copy(
            playbackPositionMs = 0L,
            isPlaying = false,
            trimStartMs = 0L,
            trimEndMs = sanitizedDuration,
            previewThumbnail = clip.thumbnail,
            adjustments = normalizedAdjustments
        )
        return updatedStream to warnings
    }

    private fun adjustForClipDefaults(
        adjustments: AdjustmentSettings,
        clip: SourceClip
    ): Pair<AdjustmentSettings, List<String>> {
        var updated = adjustments
        val warnings = mutableListOf<String>()
        val durationMs = clip.durationMs ?: 0L
        val pixelCount = (clip.width ?: 0) * (clip.height ?: 0)
        val sizeBytes = clip.sizeBytes ?: 0L
        val isLongClip = durationMs > LONG_CLIP_WARNING_MS
        val isLargeFile = sizeBytes > LARGE_CLIP_BYTES
        val isHighResolution = pixelCount > HIGH_RES_PIXEL_THRESHOLD

        if ((isLongClip || isLargeFile || isHighResolution) && adjustments.resolutionPercent > OVERSIZE_RESOLUTION) {
            updated = updated.copy(resolutionPercent = OVERSIZE_RESOLUTION)
            warnings += "Large clip detected – starting at ${(OVERSIZE_RESOLUTION * 100).roundToInt()}% resolution to protect output size."
        }

        if (adjustments.frameRate > MAX_STREAM_FPS) {
            updated = updated.copy(frameRate = MAX_STREAM_FPS)
            warnings += "Frame rate capped at ${MAX_STREAM_FPS.roundToInt()} fps for share compatibility."
        }

        if (isLargeFile) {
            warnings += "Source file weighs ${android.text.format.Formatter.formatShortFileSize(application, sizeBytes)}; consider trimming to avoid oversized GIFs."
        }

        if (isLongClip) {
            warnings += "Clip longer than ${LONG_CLIP_WARNING_MS / 1000}s detected – long renders may be slowed."
        }

        return updated to warnings.distinct()
    }

    private fun loadClipMetadata(uri: Uri): ClipMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        var lastModified: Long? = null
        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        application.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                    lastModified = cursor.getLong(modifiedIndex)
                }
            }
        }

        val retriever = MediaMetadataRetriever()
        var durationMs: Long? = null
        var width: Int? = null
        var height: Int? = null
        var frame: Bitmap? = null
        try {
            retriever.setDataSource(application, uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
            frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: RuntimeException) {
            // Some streams may not expose retrievable frames; ignore and proceed with metadata only.
        } finally {
            retriever.release()
        }

        val mimeType = application.contentResolver.getType(uri)

        return ClipMetadata(
            uri = uri,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            durationMs = durationMs,
            width = width,
            height = height,
            lastModified = lastModified,
            thumbnailBitmap = frame
        )
    }

    private data class ClipMetadata(
        val uri: Uri,
        val displayName: String?,
        val mimeType: String?,
        val sizeBytes: Long?,
        val durationMs: Long?,
        val width: Int?,
        val height: Int?,
        val lastModified: Long?,
        val thumbnailBitmap: Bitmap?
    )

    companion object {
        private const val LONG_CLIP_WARNING_MS = 30_000L
        private const val LARGE_CLIP_BYTES: Long = 50L * 1024 * 1024
        private const val HIGH_RES_PIXEL_THRESHOLD = 2_073_600
        private const val OVERSIZE_RESOLUTION = 0.6f
        private const val MAX_STREAM_FPS = 30f
    }
}

sealed interface ClipImportOutcome {
    data class Success(
        val layer: Layer,
        val warnings: List<String>,
        val importedName: String
    ) : ClipImportOutcome

    data class Failure(
        val message: String,
        val severity: LogSeverity,
        val resetLayer: Boolean = false
    ) : ClipImportOutcome
}
