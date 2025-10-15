package com.gifvision.app.ui.state.coordinators

import com.gifvision.app.media.MediaRepository
import com.gifvision.app.media.ShareRepository
import com.gifvision.app.media.ShareRequest
import com.gifvision.app.media.ShareResult
import com.gifvision.app.ui.state.Layer
import com.gifvision.app.ui.state.LogSeverity
import com.gifvision.app.ui.state.MasterBlendConfig
import com.gifvision.app.ui.state.StreamSelection

/** Handles persistence/export flows for rendered GIFs. */
internal class ShareCoordinator(
    private val mediaRepository: MediaRepository,
    private val shareRepository: ShareRepository
) {

    suspend fun shareMasterBlend(master: MasterBlendConfig): ShareActionResult {
        val request = ShareRequest(
            path = master.masterGifPath ?: return ShareActionResult(
                logMessage = "Master blend path missing",
                userMessage = "Render the master blend before sharing.",
                severity = LogSeverity.Warning,
                isError = true
            ),
            displayName = deriveDisplayName(master.masterGifPath),
            caption = master.shareSetup.caption,
            hashtags = master.shareSetup.hashtags,
            loopMetadata = master.shareSetup.loopMetadata
        )
        return when (val result = shareRepository.shareAsset(request)) {
            is ShareResult.Success -> ShareActionResult(
                logMessage = result.description,
                userMessage = result.description,
                severity = LogSeverity.Info,
                isError = false
            )
            is ShareResult.Failure -> ShareActionResult(
                logMessage = result.errorMessage,
                userMessage = result.errorMessage,
                severity = LogSeverity.Error,
                isError = true
            )
        }
    }

    suspend fun saveMasterBlend(master: MasterBlendConfig): ShareActionResult {
        val path = master.masterGifPath ?: return ShareActionResult(
            logMessage = "Master blend path missing",
            userMessage = "Render the master blend before saving.",
            severity = LogSeverity.Warning,
            isError = true
        )
        return runCatching {
            val destination = mediaRepository.exportToDownloads(path, deriveDisplayName(path))
            ShareActionResult(
                logMessage = "Master blend saved to $destination",
                userMessage = "Saved GIF to Downloads",
                severity = LogSeverity.Info,
                isError = false
            )
        }.getOrElse { throwable ->
            ShareActionResult(
                logMessage = throwable.message ?: "Unable to save GIF",
                userMessage = throwable.message ?: "Unable to save GIF",
                severity = LogSeverity.Error,
                isError = true
            )
        }
    }

    suspend fun saveStream(layer: Layer, stream: StreamSelection): ShareActionResult {
        val outputPath = when (stream) {
            StreamSelection.A -> layer.streamA.generatedGifPath
            StreamSelection.B -> layer.streamB.generatedGifPath
        } ?: return ShareActionResult(
            logMessage = "Stream ${stream.name} output missing",
            userMessage = "Generate Stream ${stream.name} before saving.",
            severity = LogSeverity.Warning,
            isError = true
        )
        return runCatching {
            val displayName = "${layer.title.replace(" ", "_")}_Stream_${stream.name}"
            val destination = mediaRepository.exportToDownloads(outputPath, displayName)
            ShareActionResult(
                logMessage = "Stream ${stream.name} saved to ${destination}",
                userMessage = "Saved Stream ${stream.name} GIF to Downloads",
                severity = LogSeverity.Info,
                isError = false
            )
        }.getOrElse { throwable ->
            val message = throwable.message ?: "Unable to save Stream ${stream.name}"
            ShareActionResult(
                logMessage = message,
                userMessage = message,
                severity = LogSeverity.Error,
                isError = true
            )
        }
    }

    private fun deriveDisplayName(path: String): String {
        val fileName = java.io.File(path).nameWithoutExtension
        return if (fileName.isNotBlank()) fileName else "gifvision_master"
    }
}

data class ShareActionResult(
    val logMessage: String,
    val userMessage: String,
    val severity: LogSeverity,
    val isError: Boolean
)
