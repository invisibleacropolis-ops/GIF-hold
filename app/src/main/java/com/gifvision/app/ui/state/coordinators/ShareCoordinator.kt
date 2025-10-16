package com.gifvision.app.ui.state.coordinators

import android.net.Uri
import com.gifvision.app.media.MediaRepository
import com.gifvision.app.media.ShareRepository
import com.gifvision.app.media.ShareRequest
import com.gifvision.app.media.ShareResult
import com.gifvision.app.ui.state.Layer
import com.gifvision.app.ui.state.LogSeverity
import com.gifvision.app.ui.state.MasterBlendConfig
import com.gifvision.app.ui.state.StreamSelection
import java.io.File
import java.util.Locale

/** Handles persistence/export flows for rendered GIFs. */
internal class ShareCoordinator(
    private val mediaRepository: MediaRepository,
    private val shareRepository: ShareRepository
) {

    suspend fun shareMasterBlend(master: MasterBlendConfig): ShareActionResult {
        val resolvedPath = master.masterGifPath ?: return ShareActionResult(
            logMessage = "Master blend path missing",
            userMessage = "Render the master blend before sharing.",
            severity = LogSeverity.Warning,
            isError = true
        )
        val request = ShareRequest(
            path = resolvedPath,
            displayName = deriveDisplayName(resolvedPath),
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
        return exportToDownloads(
            path = path,
            displayName = deriveDisplayName(path),
            successMessage = "Saved GIF to Downloads",
            logContext = "Master blend"
        )
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
        val displayName = buildStreamDisplayName(layer.title, stream)
        return exportToDownloads(
            path = outputPath,
            displayName = displayName,
            successMessage = "Saved Stream ${stream.name} GIF to Downloads",
            logContext = "Stream ${stream.name}"
        )
    }

    private suspend fun exportToDownloads(
        path: String,
        displayName: String,
        successMessage: String,
        logContext: String
    ): ShareActionResult {
        return runCatching {
            val destination = mediaRepository.exportToDownloads(path, displayName)
            ShareActionResult(
                logMessage = "$logContext saved to ${destination}",
                userMessage = successMessage,
                severity = LogSeverity.Info,
                isError = false,
                destination = destination.toString()
            )
        }.getOrElse { throwable ->
            val fallback = throwable.message ?: "Unable to save GIF"
            ShareActionResult(
                logMessage = fallback,
                userMessage = fallback,
                severity = LogSeverity.Error,
                isError = true
            )
        }
    }

    private fun deriveDisplayName(path: String): String {
        val fileCandidate = File(path)
            .takeIf { it.nameWithoutExtension.isNotBlank() }
            ?.nameWithoutExtension
        val lastSegment = path.substringAfterLast('/', missingDelimiterValue = "")
        val raw = when {
            fileCandidate != null -> fileCandidate
            lastSegment.isNotBlank() -> lastSegment.substringBefore('.')
            else -> null
        }
        val sanitized = sanitizeDisplayName(raw)
        return if (sanitized.isBlank() || sanitized == GENERIC_MASTER_TOKEN) {
            DEFAULT_MASTER_NAME
        } else {
            sanitized
        }
    }

    private fun buildStreamDisplayName(layerTitle: String, stream: StreamSelection): String {
        val sanitizedLayer = sanitizeDisplayName(layerTitle)
        val layerSection = if (sanitizedLayer.isBlank()) "layer" else sanitizedLayer
        return "${layerSection}_stream_${stream.name.lowercase(Locale.US)}"
    }

    private fun sanitizeDisplayName(raw: String?): String {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return ""
        return candidate
            .replace(Regex("[^A-Za-z0-9_-]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .lowercase(Locale.US)
    }

    private companion object {
        const val DEFAULT_MASTER_NAME = "gifvision_master"
        const val GENERIC_MASTER_TOKEN = "master"
    }
}

data class ShareActionResult(
    val logMessage: String,
    val userMessage: String,
    val severity: LogSeverity,
    val isError: Boolean,
    val destination: String? = null
)
