package com.gifvision.app.ui.resources

import android.net.Uri

/**
 * Centralizes standard log copy so UI surfaces and view-model flows stay consistent when reporting
 * progress, file access issues, or blocked actions. Consolidating these helpers in Phase 1 keeps
 * string construction colocated without altering behaviour, making future refactors less error
 * prone.
 */
internal object LogCopy {

    private const val FALLBACK_PERMISSION_REASON = "permission revoked"
    private const val FALLBACK_UNKNOWN_REASON = "unknown"

    fun persistablePermissionDenied(reason: String?): String {
        return "Persistable permission denied: ${reason ?: FALLBACK_UNKNOWN_REASON}"
    }

    fun accessRevoked(uri: Uri, reason: String?): String {
        return "Access revoked for ${uri.lastPathSegment ?: uri}: ${reason ?: FALLBACK_PERMISSION_REASON}"
    }

    fun storagePermissionRevoked(uri: Uri, reason: String?): String {
        return "Storage permission revoked for ${uri.lastPathSegment ?: uri}: ${reason ?: FALLBACK_PERMISSION_REASON}"
    }

    fun jobProgress(jobId: String, percent: Int): String {
        return "$jobId progress $percent%"
    }

    fun jobComplete(jobId: String, path: String): String {
        return "$jobId complete -> $path"
    }

    fun blendError(message: String?): String {
        return "Blend error: $message"
    }

    fun gifFileNotFound(label: String, path: String): String {
        return "$label GIF file not found: $path"
    }

    fun shareBlockedMasterNotReady(): String {
        return "Share blocked – master blend not ready"
    }

    fun saveBlockedMasterNotReady(): String {
        return "Save blocked – master blend not ready"
    }

    fun saveBlockedStreamNotRendered(streamName: String): String {
        return "Save blocked – Stream $streamName not rendered yet"
    }
}
