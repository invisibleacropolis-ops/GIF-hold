package com.gifvision.app.media

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.gifvision.app.ui.state.GifLoopMetadata
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Encapsulates share intents and storage grants for generated GIFs. By routing share logic through
 * this abstraction the UI and view-model remain oblivious to Android's `Intent` API surface and can
 * be tested with simple doubles.
 */
interface ShareRepository {
    /** Attempts to share an asset represented by the supplied [request]. */
    suspend fun shareAsset(request: ShareRequest): ShareResult
}

/** Payload describing a share request coming from the UI layer. */
data class ShareRequest(
    val path: String,
    val displayName: String,
    val caption: String,
    val hashtags: List<String>,
    val loopMetadata: GifLoopMetadata
)

/** Outcome of a share attempt. */
sealed class ShareResult {
    data class Success(val description: String) : ShareResult()
    data class Failure(val errorMessage: String, val throwable: Throwable? = null) : ShareResult()
}

/**
 * Preview implementation that simply reports back the file path instead of kicking off a chooser
 * intent. The calling code can log the response for debugging purposes.
 */
class LoggingShareRepository : ShareRepository {
    override suspend fun shareAsset(request: ShareRequest): ShareResult {
        return ShareResult.Success("Share intent prepared for ${request.path}")
    }
}

/**
 * Android implementation that leverages FileProvider + ACTION_SEND to surface the platform chooser.
 * The repository converts filesystem paths into temporary `content://` URIs and grants read
 * permissions to the receiving apps.
 */
class AndroidShareRepository(
    private val context: Context,
    private val authority: String = "${context.packageName}.fileprovider",
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) : ShareRepository {

    override suspend fun shareAsset(request: ShareRequest): ShareResult = withContext(dispatcher) {
        runCatching {
            val shareUri = prepareShareUri(context.contentResolver, request.path)
            val captionSection = request.caption.trim()
            val hashtagSection = request.hashtags
                .filter { it.isNotBlank() }
                .joinToString(separator = " ") { tag ->
                    val normalized = if (tag.startsWith("#")) tag else "#${tag.trim()}"
                    normalized
                }
                .trim()
            val loopSection = "Loop: ${request.loopMetadata.displayName}"
            val payloadParts = listOf(captionSection, hashtagSection, loopSection)
                .filter { it.isNotBlank() }
            val shareText = payloadParts.joinToString(separator = "\n\n")

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = GIF_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_TEXT, shareText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.grantUriPermissionToIntent(shareUri, intent)
            val chooser = Intent.createChooser(intent, "Share ${request.displayName}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)

            ShareResult.Success("Share sheet opened for ${request.displayName}")
        }.getOrElse { throwable ->
            val error = throwable.message ?: "Unable to share GIF"
            ShareResult.Failure(error, throwable)
        }
    }

    private fun prepareShareUri(contentResolver: ContentResolver, path: String): Uri {
        val parsed = Uri.parse(path)
        if (parsed.scheme == ContentResolver.SCHEME_CONTENT || parsed.scheme == "data") {
            return parsed
        }
        val file = File(path)
        if (!file.exists()) {
            throw FileNotFoundException("Missing file at $path")
        }
        return FileProvider.getUriForFile(context, authority, file)
    }

    private fun Context.grantUriPermissionToIntent(uri: Uri, intent: Intent) {
        val resolved = packageManager?.queryIntentActivities(intent, 0) ?: return
        resolved.forEach { info ->
            grantUriPermission(info.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private companion object {
        const val GIF_MIME_TYPE = "image/gif"
    }
}
