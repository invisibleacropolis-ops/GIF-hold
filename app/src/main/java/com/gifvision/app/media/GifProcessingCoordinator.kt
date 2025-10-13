package com.gifvision.app.media

import android.content.Context
import android.graphics.Color
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.LogCallback
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.antonkarpenko.ffmpegkit.Statistics
import com.antonkarpenko.ffmpegkit.StatisticsCallback
import com.gifvision.app.ui.state.AdjustmentSettings
import com.gifvision.app.ui.state.GifVisionBlendMode
import com.gifvision.app.ui.state.StreamSelection
import java.io.File
import java.text.DecimalFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Coordinates FFmpeg workloads. The interface purposefully hides how jobs are executed so the
 * view-model only cares about high level progress events instead of WorkManager specifics.
 */
interface GifProcessingCoordinator {
    /** Launches a render job for a single stream (Layer X Stream A/B). */
    fun renderStream(request: StreamProcessingRequest): Flow<GifProcessingEvent>

    /** Launches a blend job that combines Stream A and B for a given layer. */
    fun blendLayer(request: LayerBlendRequest): Flow<GifProcessingEvent>

    /** Launches the final master blend combining the outputs of Layer 1 and Layer 2. */
    fun mergeMaster(request: MasterBlendRequest): Flow<GifProcessingEvent>
}

/** Payload describing a stream render job. */
data class StreamProcessingRequest(
    val layerId: Int,
    val stream: StreamSelection,
    val sourcePath: String,
    val adjustments: AdjustmentSettings,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val suggestedOutputPath: String? = null,
    val jobId: String = "layer-${layerId}-stream-${stream.name.lowercase()}-render"
)

/** Payload describing a layer blend job. */
data class LayerBlendRequest(
    val layerId: Int,
    val streamAPath: String,
    val streamBPath: String,
    val blendMode: GifVisionBlendMode,
    val opacity: Float,
    val suggestedOutputPath: String? = null,
    val jobId: String = "layer-${layerId}-blend-${blendMode.name.lowercase()}"
)

/** Payload describing the final master blend job. */
data class MasterBlendRequest(
    val layerOnePath: String,
    val layerTwoPath: String,
    val blendMode: GifVisionBlendMode,
    val opacity: Float,
    val suggestedOutputPath: String? = null,
    val jobId: String = "master-blend-${blendMode.name.lowercase()}-${UUID.randomUUID()}"
)

/**
 * Stream of status updates emitted by the FFmpeg orchestration layer.
 */
sealed class GifProcessingEvent(open val jobId: String) {
    data class Started(override val jobId: String, val message: String? = null) : GifProcessingEvent(jobId)
    data class Progress(
        override val jobId: String,
        val progress: Float,
        val message: String? = null
    ) : GifProcessingEvent(jobId)

    data class Completed(
        override val jobId: String,
        val outputPath: String,
        val logs: List<String> = emptyList()
    ) : GifProcessingEvent(jobId)

    data class Failed(override val jobId: String, val throwable: Throwable) : GifProcessingEvent(jobId)
    data class Cancelled(override val jobId: String) : GifProcessingEvent(jobId)
}

/**
 * Developer friendly coordinator that simulates FFmpeg work with a few progress events. The class
 * writes verbose log entries so the Compose previews remain informative even without a native
 * binary executing.
 */
class LoggingGifProcessingCoordinator : GifProcessingCoordinator {
    override fun renderStream(request: StreamProcessingRequest): Flow<GifProcessingEvent> = flow {
        emit(
            GifProcessingEvent.Started(
                request.jobId,
                "Queued render for Stream ${request.stream.name} (trim ${request.trimStartMs}-${request.trimEndMs}ms)"
            )
        )
        delay(40)
        emit(GifProcessingEvent.Progress(request.jobId, 0.35f, "Analyzing adjustments"))
        delay(40)
        emit(
            GifProcessingEvent.Completed(
                jobId = request.jobId,
                outputPath = request.suggestedOutputPath
                    ?: "render/${request.jobId}_${UUID.randomUUID()}.gif",
                logs = listOf(
                    "Rendered stream ${request.stream.name} at ${request.adjustments.frameRate} fps",
                    "Segment ${request.trimStartMs}ms -> ${request.trimEndMs}ms"
                )
            )
        )
    }

    override fun blendLayer(request: LayerBlendRequest): Flow<GifProcessingEvent> = flow {
        emit(GifProcessingEvent.Started(request.jobId, "Merging streams for Layer ${request.layerId}"))
        delay(40)
        emit(GifProcessingEvent.Progress(request.jobId, 0.5f, "Applying ${request.blendMode.displayName}"))
        delay(40)
        emit(
            GifProcessingEvent.Completed(
                jobId = request.jobId,
                outputPath = request.suggestedOutputPath
                    ?: "render/${request.jobId}_${UUID.randomUUID()}.gif",
                logs = listOf("Layer ${request.layerId} blend complete at opacity ${request.opacity}")
            )
        )
    }

    override fun mergeMaster(request: MasterBlendRequest): Flow<GifProcessingEvent> = flow {
        emit(GifProcessingEvent.Started(request.jobId, "Master blend started"))
        delay(40)
        emit(GifProcessingEvent.Progress(request.jobId, 0.6f, "Combining layer outputs"))
        delay(40)
        emit(
            GifProcessingEvent.Completed(
                jobId = request.jobId,
                outputPath = request.suggestedOutputPath
                    ?: "render/${request.jobId}_${UUID.randomUUID()}.gif",
                logs = listOf("Master blend finished at opacity ${request.opacity}")
            )
        )
    }
}

/**
 * Foreground notification hook that mirrors the expectations of Android's foreground service
 * contract without forcing this module to depend on a specific Notification implementation.
 */
interface GifProcessingNotificationAdapter {
    /** Invoked when a render job starts so the caller can promote the work to a foreground task. */
    fun onJobStarted(jobId: String, title: String, text: String)

    /** Updates the user-visible progress indicator for an active job. */
    fun onProgress(jobId: String, progress: Float)

    /** Streams FFmpeg stdout/stderr messages into any active notification or UI surface. */
    fun onLog(jobId: String, logLine: String)

    /** Signals that the foreground work can be dismissed. */
    fun onJobFinished(jobId: String)

    /** No-op adapter used during previews or tests. */
    object Noop : GifProcessingNotificationAdapter {
        override fun onJobStarted(jobId: String, title: String, text: String) = Unit
        override fun onProgress(jobId: String, progress: Float) = Unit
        override fun onLog(jobId: String, logLine: String) = Unit
        override fun onJobFinished(jobId: String) = Unit
    }
}

/**
 * Production-ready coordinator that executes FFmpegKit commands on a dedicated IO dispatcher,
 * translates [AdjustmentSettings] into palette-aware GIF filter graphs, and persists all
 * intermediate artifacts via the supplied [MediaRepository]. Each job is surfaced through a
 * foreground notification to align with Android's background execution limits.
 */
class FfmpegKitGifProcessingCoordinator(
    private val context: Context,
    private val mediaRepository: MediaRepository,
    private val notificationAdapter: GifProcessingNotificationAdapter = GifProcessingNotificationAdapter.Noop,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : GifProcessingCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    override fun renderStream(request: StreamProcessingRequest): Flow<GifProcessingEvent> =
        managedJobFlow(
            jobId = request.jobId,
            jobLabel = "Stream ${request.stream.name}"
        ) { logs, progressCallback ->
            val output = resolveStreamOutput(context, request)
            val command = buildStreamCommand(request, output)
            executeCommand(command, logs, progressCallback)

            val stored = mediaRepository.storeStreamOutput(
                layerId = request.layerId,
                stream = request.stream,
                suggestedPath = output.absolutePath
            )
            GifProcessingEvent.Completed(request.jobId, stored.path, logs.toList())
        }

    override fun blendLayer(request: LayerBlendRequest): Flow<GifProcessingEvent> =
        managedJobFlow(
            jobId = request.jobId,
            jobLabel = "Layer ${request.layerId} Blend"
        ) { logs, progressCallback ->
            val output = resolveLayerOutput(context, request)
            val command = buildBlendCommand(request, output)
            executeCommand(command, logs, progressCallback)

            val stored = mediaRepository.storeLayerBlend(request.layerId, output.absolutePath)
            GifProcessingEvent.Completed(request.jobId, stored.path, logs.toList())
        }

    override fun mergeMaster(request: MasterBlendRequest): Flow<GifProcessingEvent> =
        managedJobFlow(
            jobId = request.jobId,
            jobLabel = "Master Blend"
        ) { logs, progressCallback ->
            val output = resolveMasterOutput(context, request)
            val command = buildMasterBlendCommand(request, output)
            executeCommand(command, logs, progressCallback)

            val stored = mediaRepository.storeMasterBlend(output.absolutePath)
            GifProcessingEvent.Completed(request.jobId, stored.path, logs.toList())
        }

    /**
     * Shared orchestration wrapper that reports job lifecycle events, captures FFmpeg logs, and
     * forwards the [GifProcessingEvent] emitted by the supplied [block].
     */
    private fun managedJobFlow(
        jobId: String,
        jobLabel: String,
        block: suspend (MutableList<String>, suspend (Float) -> Unit) -> GifProcessingEvent
    ): Flow<GifProcessingEvent> = callbackFlow {
        val started = AtomicBoolean(false)
        val job = scope.launch {
            val logs = mutableListOf<String>()
            val progressFormatter = DecimalFormat("#0.0%")
            try {
                notificationAdapter.onJobStarted(jobId, jobLabel, "Preparing FFmpeg pipeline")
                trySend(GifProcessingEvent.Started(jobId, "Starting $jobLabel"))
                started.set(true)
                val event = block(logs) { progress ->
                    val safeProgress = progress.coerceIn(0f, 0.999f)
                    notificationAdapter.onProgress(jobId, safeProgress)
                    trySend(
                        GifProcessingEvent.Progress(
                            jobId,
                            safeProgress,
                            "${progressFormatter.format(safeProgress)} complete"
                        )
                    )
                }
                trySend(event)
            } catch (throwable: Throwable) {
                if (!started.get()) {
                    trySend(GifProcessingEvent.Started(jobId, "Starting $jobLabel"))
                }
                if (throwable is CancellationException) {
                    trySend(GifProcessingEvent.Cancelled(jobId))
                } else {
                    logs += throwable.message ?: throwable.toString()
                    trySend(GifProcessingEvent.Failed(jobId, throwable))
                }
            } finally {
                notificationAdapter.onJobFinished(jobId)
                close()
            }
        }

        awaitClose {
            if (job.isActive) {
                job.cancel()
            }
        }
    }

    /** Executes the prepared FFmpeg command and routes stdout/stderr to the provided [logs]. */
    private suspend fun executeCommand(
        command: FfmpegCommand,
        logs: MutableList<String>,
        progressCallback: suspend (Float) -> Unit
    ) = withContext(dispatcher) {
        val durationMs = max(1L, command.estimatedDurationMillis).toFloat()
        val statisticsCallback = StatisticsCallback { stats ->
            val ratio = stats.progressRatio(durationMs)
            if (!ratio.isNaN()) {
                scope.launch { progressCallback(ratio) }
            }
        }

        val logCallback = LogCallback { log ->
            val line = log?.message ?: return@LogCallback
            if (line.isBlank()) return@LogCallback
            logs += line
            notificationAdapter.onLog(command.jobId, line)
            // Also log to Android logcat for debugging
            android.util.Log.d("FFmpegKit", "FFmpeg: $line")
        }

        // Log the complete command for debugging
        val commandString = command.arguments.joinToString(" ") { it.quoteIfNeeded() }
        android.util.Log.d("FFmpegKit", "============ EXECUTING FFMPEG COMMAND ============")
        android.util.Log.d("FFmpegKit", "Job ID: ${command.jobId}")
        android.util.Log.d("FFmpegKit", "Command: $commandString")
        android.util.Log.d("FFmpegKit", "==================================================")
        logs += "Command: $commandString"

        try {
            // Use 5 minute timeout for complex operations (blending, looping, scaling)
            val session = withTimeout(300_000L) {
                executeAsync(command, statisticsCallback, logCallback)
            }
            
            android.util.Log.d("FFmpegKit", "Session completed with returnCode: ${session.returnCode}")
            
            if (ReturnCode.isCancel(session.returnCode)) {
                throw CancellationException("FFmpeg cancelled")
            }
            if (!ReturnCode.isSuccess(session.returnCode)) {
                val message = buildString {
                    append("FFmpeg failed with code ${session.returnCode}. ")
                    session.failStackTrace?.let { append("Stack trace: $it\n") }
                    // Add last few log lines for context
                    val lastLogs = logs.takeLast(10).joinToString("\n")
                    append("\nRecent logs:\n$lastLogs")
                }
                android.util.Log.e("FFmpegKit", "============ FFMPEG ERROR ============")
                android.util.Log.e("FFmpegKit", message)
                android.util.Log.e("FFmpegKit", "======================================")
                throw IllegalStateException(message)
            }
            
            android.util.Log.d("FFmpegKit", "FFmpeg command completed successfully")
        } catch (e: TimeoutCancellationException) {
            val message = "FFmpeg command timed out after 5 minutes - check if input files are valid"
            logs += message
            android.util.Log.e("FFmpegKit", message)
            throw IllegalStateException(message)
        } catch (e: Exception) {
            // Log the full exception for debugging
            android.util.Log.e("FFmpegKit", "Exception during FFmpeg execution", e)
            throw e
        }
    }

    /** Launches an asynchronous FFmpeg session and awaits completion in a suspendable manner. */
    private suspend fun executeAsync(
        command: FfmpegCommand,
        statisticsCallback: StatisticsCallback,
        logCallback: LogCallback
    ): FFmpegSession = suspendCancellableCoroutine { continuation ->
        val commandString = command.arguments.joinToString(" ") { it.quoteIfNeeded() }
        
        val session = FFmpegKit.executeAsync(
            commandString,
            { result ->
                if (continuation.isActive) {
                    // Log completion for debugging
                    android.util.Log.d("FFmpegKit", "Session ${command.jobId} completed with returnCode: ${result.returnCode}")
                    continuation.resumeWith(Result.success(result))
                }
            },
            logCallback,
            statisticsCallback
        )

        continuation.invokeOnCancellation {
            android.util.Log.d("FFmpegKit", "Session ${command.jobId} cancelled")
            session.cancel()
        }
    }

    companion object {
        private const val RENDER_DIRECTORY = "renders"

        /** 
         * Queries and logs all available FFmpeg filters in the current FFmpegKit build.
         * Useful for debugging which filters are actually available.
         */
        fun logAvailableFilters() {
            try {
                android.util.Log.d("FFmpegKit", "Querying available filters...")
                val session = FFmpegKit.execute("-filters")
                val output = session.output ?: ""
                
                android.util.Log.d("FFmpegKit", "============ AVAILABLE FFMPEG FILTERS ============")
                android.util.Log.d("FFmpegKit", output)
                android.util.Log.d("FFmpegKit", "==================================================")
                
                // Parse and categorize filters
                val videoFilters = output.lines()
                    .filter { it.contains("->") && (it.contains("V->V") || it.contains("|->V")) }
                    .map { it.trim() }
                
                android.util.Log.d("FFmpegKit", "Found ${videoFilters.size} video filters")
                android.util.Log.d("FFmpegKit", "Video filters: ${videoFilters.take(20).joinToString(", ")}")
                
                // Specifically check for drawtext
                val hasDrawtext = output.contains(" drawtext ")
                android.util.Log.d("FFmpegKit", "drawtext filter available: $hasDrawtext")
                
            } catch (e: Exception) {
                android.util.Log.e("FFmpegKit", "Error querying filters", e)
            }
        }
        
        /**
         * Checks if a specific filter is available in the current FFmpegKit build.
         */
        fun isFilterAvailable(filterName: String): Boolean {
            return try {
                val session = FFmpegKit.execute("-filters")
                val output = session.output ?: ""
                output.contains(" $filterName ")
            } catch (e: Exception) {
                android.util.Log.e("FFmpegKit", "Error checking filter availability", e)
                false
            }
        }
        
        /**
         * Verifies that the drawtext filter and font support are properly configured.
         * Should be called during app initialization to catch configuration issues early.
         */
        fun verifyDrawtextSupport(): Boolean {
            android.util.Log.d("FFmpegKit", "Verifying drawtext filter support...")
            
            // Check if drawtext filter is available
            if (!isFilterAvailable("drawtext")) {
                android.util.Log.e("FFmpegKit", "ERROR: drawtext filter not available in FFmpegKit build!")
                android.util.Log.e("FFmpegKit", "The FFmpeg build must be compiled with --enable-libfreetype")
                return false
            }
            
            // Check if we can find a usable font
            val fontPath = findAvailableFont()
            if (fontPath == null) {
                android.util.Log.e("FFmpegKit", "ERROR: No TrueType fonts found on device!")
                android.util.Log.e("FFmpegKit", "Searched in /system/fonts/ for .ttf files")
                return false
            }
            
            android.util.Log.i("FFmpegKit", "✓ drawtext filter is available")
            android.util.Log.i("FFmpegKit", "✓ Font file found: $fontPath")
            
            // Test a simple drawtext command to verify it works
            try {
                android.util.Log.d("FFmpegKit", "Testing drawtext filter with a simple command...")
                val testCommand = "-f lavfi -i color=c=black:s=100x100:d=1 -vf \"drawtext=fontfile=$fontPath:text='TEST':fontsize=20:fontcolor=white\" -frames:v 1 -f null -"
                val session = FFmpegKit.execute(testCommand)
                
                if (ReturnCode.isSuccess(session.returnCode)) {
                    android.util.Log.i("FFmpegKit", "✓ drawtext filter test PASSED")
                    return true
                } else {
                    android.util.Log.e("FFmpegKit", "✗ drawtext filter test FAILED")
                    android.util.Log.e("FFmpegKit", "Test output: ${session.output}")
                    return false
                }
            } catch (e: Exception) {
                android.util.Log.e("FFmpegKit", "✗ drawtext filter test threw exception", e)
                return false
            }
        }

        /** Prepares the filesystem location for a stream render output. */
        fun resolveStreamOutput(context: Context, request: StreamProcessingRequest): File {
            val fileName = request.suggestedOutputPath?.let { File(it).name }
                ?: "layer_${request.layerId}_stream_${request.stream.name.lowercase(Locale.ROOT)}.gif"
            return prepareOutputFile(context, fileName)
        }

        /** Prepares the filesystem location for a per-layer blend output. */
        fun resolveLayerOutput(context: Context, request: LayerBlendRequest): File {
            val fileName = request.suggestedOutputPath?.let { File(it).name }
                ?: "layer_${request.layerId}_blend_${request.blendMode.name.lowercase(Locale.ROOT)}.gif"
            return prepareOutputFile(context, fileName)
        }

        /** Prepares the filesystem location for the master blend output. */
        fun resolveMasterOutput(context: Context, request: MasterBlendRequest): File {
            val fileName = request.suggestedOutputPath?.let { File(it).name }
                ?: "master_blend_${request.blendMode.name.lowercase(Locale.ROOT)}.gif"
            return prepareOutputFile(context, fileName)
        }

        /**
         * Finds the first available TrueType font on the Android system.
         * Returns the absolute path to the font file, or null if no fonts are found.
         */
        fun findAvailableFont(): String? {
            // List of common Android system fonts in priority order
            val fontCandidates = listOf(
                "/system/fonts/Roboto-Regular.ttf",      // Modern Android (5.0+)
                "/system/fonts/DroidSans.ttf",           // Older Android
                "/system/fonts/NotoSans-Regular.ttf",    // Alternative
                "/system/fonts/Roboto-Light.ttf",        // Roboto variant
                "/system/fonts/DroidSans-Bold.ttf",      // Bold variant fallback
                "/system/fonts/NotoSerif-Regular.ttf"    // Serif fallback
            )
            
            for (fontPath in fontCandidates) {
                val fontFile = File(fontPath)
                if (fontFile.exists() && fontFile.canRead()) {
                    android.util.Log.d("FFmpegKit", "Found available font: $fontPath")
                    return fontPath
                }
            }
            
            // If no system fonts found, try to list what's actually in /system/fonts/
            try {
                val systemFontsDir = File("/system/fonts")
                if (systemFontsDir.exists() && systemFontsDir.isDirectory) {
                    val availableFonts = systemFontsDir.listFiles { file ->
                        file.isFile && file.name.endsWith(".ttf", ignoreCase = true)
                    }
                    
                    if (!availableFonts.isNullOrEmpty()) {
                        val firstFont = availableFonts.first().absolutePath
                        android.util.Log.d("FFmpegKit", "Using first available .ttf font: $firstFont")
                        return firstFont
                    } else {
                        android.util.Log.w("FFmpegKit", "No .ttf fonts found in /system/fonts/")
                    }
                } else {
                    android.util.Log.w("FFmpegKit", "/system/fonts/ directory not accessible")
                }
            } catch (e: Exception) {
                android.util.Log.e("FFmpegKit", "Error scanning for fonts", e)
            }
            
            return null
        }

        private fun prepareOutputFile(context: Context, fileName: String): File {
            val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, RENDER_DIRECTORY)
            if (!directory.exists()) {
                directory.mkdirs()
            }
            return File(directory, fileName)
        }
    }
}

/** Immutable structure describing an FFmpeg invocation. */
data class FfmpegCommand(
    val jobId: String,
    val arguments: List<String>,
    val estimatedDurationMillis: Long
)

/** Builds the FFmpeg invocation for a stream render using palette generation. */
private fun buildStreamCommand(
    request: StreamProcessingRequest,
    output: File
): FfmpegCommand {
    val durationMillis = max(
        1L,
        if (request.trimEndMs > request.trimStartMs) {
            request.trimEndMs - request.trimStartMs
        } else {
            (request.adjustments.clipDurationSeconds * 1_000).roundToInt().toLong()
        }
    )
    val filterGraph = buildStreamFilterGraph(request.adjustments)
    val args = buildList {
        add("-y")
        val startSeconds = request.trimStartMs / 1_000f
        if (startSeconds > 0f) {
            add("-ss")
            add(startSeconds.toFfmpegString())
        }
        add("-i")
        add(request.sourcePath)
        add("-t")
        add((durationMillis / 1_000f).toFfmpegString())
        add("-filter_complex")
        add(filterGraph.asFilterArgument())
        add("-map")
        add("[out]")
        add("-gifflags")
        add("+transdiff")
        add(output.absolutePath)
    }
    return FfmpegCommand(request.jobId, args, durationMillis)
}

/**
 * Represents the dimensions of a GIF file.
 */
data class GifDimensions(val width: Int, val height: Int) {
    val area: Int get() = width * height
}

/**
 * Retrieves the dimensions of a GIF file using FFprobe.
 * Returns null if dimensions cannot be determined.
 */
private suspend fun getGifDimensions(filePath: String): GifDimensions? = withContext(Dispatchers.IO) {
    try {
        val session = FFprobeKit.execute(
            "-v error -select_streams v:0 -show_entries stream=width,height -of csv=s=x:p=0 \"$filePath\""
        )
        
        if (ReturnCode.isSuccess(session.returnCode)) {
            val output = session.output?.trim()
            val parts = output?.split("x")
            if (parts?.size == 2) {
                val width = parts[0].toIntOrNull()
                val height = parts[1].toIntOrNull()
                if (width != null && height != null && width > 0 && height > 0) {
                    return@withContext GifDimensions(width, height)
                }
            }
            android.util.Log.w("FFmpegKit", "Could not parse dimensions from: $output")
        } else {
            android.util.Log.w("FFmpegKit", "Could not get dimensions for $filePath: ${session.output}")
        }
        null
    } catch (e: Exception) {
        android.util.Log.e("FFmpegKit", "Error getting dimensions for $filePath", e)
        null
    }
}

/**
 * Retrieves the duration of a GIF file in seconds using FFprobe.
 * Returns null if duration cannot be determined.
 */
private suspend fun getGifDuration(filePath: String): Float? = withContext(Dispatchers.IO) {
    try {
        val session = FFprobeKit.execute(
            "-v error -select_streams v:0 -show_entries stream=duration -of default=noprint_wrappers=1:nokey=1 \"$filePath\""
        )
        
        if (ReturnCode.isSuccess(session.returnCode)) {
            val output = session.output?.trim()
            output?.toFloatOrNull()
        } else {
            android.util.Log.w("FFmpegKit", "Could not get duration for $filePath: ${session.output}")
            null
        }
    } catch (e: Exception) {
        android.util.Log.e("FFmpegKit", "Error getting duration for $filePath", e)
        null
    }
}

/** Builds the FFmpeg invocation for either a layer or master blend job. */
private suspend fun buildBlendCommand(
    jobId: String,
    primaryPath: String,
    secondaryPath: String,
    blendMode: GifVisionBlendMode,
    opacity: Float,
    output: File
): FfmpegCommand {
    // Get durations and dimensions of both GIFs
    val primaryDuration = getGifDuration(primaryPath)
    val secondaryDuration = getGifDuration(secondaryPath)
    val primaryDimensions = getGifDimensions(primaryPath)
    val secondaryDimensions = getGifDimensions(secondaryPath)
    
    var estimatedDurationMs = 15_000L
    var needsDurationMatch = false
    var maxDuration = 0f
    var needsDimensionMatch = false
    var targetWidth = 0
    var targetHeight = 0
    var scaleSecondary = false
    
    // Check duration mismatch
    if (primaryDuration != null && secondaryDuration != null && 
        primaryDuration != secondaryDuration) {
        
        maxDuration = maxOf(primaryDuration, secondaryDuration)
        estimatedDurationMs = (maxDuration * 1000).toLong()
        needsDurationMatch = true
        
        android.util.Log.d("FFmpegKit", 
            "Duration mismatch detected - Primary: ${primaryDuration}s, Secondary: ${secondaryDuration}s")
    }
    
    // Check dimension mismatch
    if (primaryDimensions != null && secondaryDimensions != null) {
        if (primaryDimensions.width != secondaryDimensions.width || 
            primaryDimensions.height != secondaryDimensions.height) {
            
            needsDimensionMatch = true
            
            // Scale to the LARGER dimensions to avoid quality loss
            if (primaryDimensions.area >= secondaryDimensions.area) {
                targetWidth = primaryDimensions.width
                targetHeight = primaryDimensions.height
                scaleSecondary = true
                android.util.Log.d("FFmpegKit", 
                    "Dimension mismatch - Scaling secondary (${secondaryDimensions.width}x${secondaryDimensions.height}) " +
                    "to match primary (${primaryDimensions.width}x${primaryDimensions.height})")
            } else {
                targetWidth = secondaryDimensions.width
                targetHeight = secondaryDimensions.height
                scaleSecondary = false
                android.util.Log.d("FFmpegKit", 
                    "Dimension mismatch - Scaling primary (${primaryDimensions.width}x${primaryDimensions.height}) " +
                    "to match secondary (${secondaryDimensions.width}x${secondaryDimensions.height})")
            }
        } else {
            android.util.Log.d("FFmpegKit", 
                "Dimensions match: ${primaryDimensions.width}x${primaryDimensions.height}")
        }
    }
    
    val args = buildList {
        add("-y")
        
        // Handle duration looping if needed
        if (needsDurationMatch && primaryDuration != null && secondaryDuration != null) {
            if (primaryDuration < secondaryDuration) {
                val loopCount = kotlin.math.ceil(secondaryDuration / primaryDuration).toInt()
                android.util.Log.d("FFmpegKit", 
                    "Looping primary input $loopCount times to match secondary duration " +
                    "(${primaryDuration}s * $loopCount = ${primaryDuration * loopCount}s)")
                
                add("-stream_loop")
                add(loopCount.toString())
                add("-i")
                add(primaryPath)
                add("-i")
                add(secondaryPath)
            } else {
                val loopCount = kotlin.math.ceil(primaryDuration / secondaryDuration).toInt()
                android.util.Log.d("FFmpegKit", 
                    "Looping secondary input $loopCount times to match primary duration " +
                    "(${secondaryDuration}s * $loopCount = ${secondaryDuration * loopCount}s)")
                
                add("-i")
                add(primaryPath)
                add("-stream_loop")
                add(loopCount.toString())
                add("-i")
                add(secondaryPath)
            }
        } else {
            // No duration looping needed
            add("-i")
            add(primaryPath)
            add("-i")
            add(secondaryPath)
        }
        
        // Build filter graph with both duration and dimension matching
        val filterGraph = buildBlendFilterGraph(
            blendMode = blendMode,
            opacity = opacity,
            matchDuration = needsDurationMatch,
            maxDuration = maxDuration,
            matchDimensions = needsDimensionMatch,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            scaleSecondary = scaleSecondary
        )
        add("-filter_complex")
        add(filterGraph.asFilterArgument())
        
        add("-map")
        add("[out]")
        add("-gifflags")
        add("+transdiff")
        add(output.absolutePath)
    }
    
    return FfmpegCommand(jobId, args, estimatedDurationMs)
}

/** Creates the complex filter graph for a single stream render. */
private fun buildStreamFilterGraph(settings: AdjustmentSettings): String {
    val stages = mutableListOf<String>()
    val filters = mutableListOf<String>()
    filters += "setpts=PTS-STARTPTS"
    filters += buildFrameRateFilter(settings.frameRate)
    filters += buildScaleFilter(settings.resolutionPercent)
    buildEqFilter(settings)?.let(filters::add)
    buildHueFilter(settings.hue)?.let(filters::add)
    buildSepiaFilter(settings.sepia)?.let(filters::add)
    buildColorBalanceFilter(settings)?.let(filters::add)
    buildColorCycleFilter(settings.colorCycleSpeed)?.let(filters::add)
    buildMotionTrailFilter(settings.motionTrails)?.let(filters::add)
    buildSharpenFilter(settings.sharpen)?.let(filters::add)
    buildPixellateFilter(settings.pixellate)?.let(filters::add)
    buildEdgeFilter(settings)?.let(filters::add)
    if (settings.negateColors) {
        filters += "negate=enable='gte(t,0)'"
    }
    if (settings.flipHorizontal) {
        filters += "hflip"
    }
    if (settings.flipVertical) {
        filters += "vflip"
    }
    buildDrawTextFilter(settings)?.let(filters::add)
    filters += "format=rgba"

    val baseStage = "[0:v]${filters.joinToString(",")}[prepal]"
    val splitStage = "[prepal]split[palin][gifin]"
    val paletteStage = "[palin]palettegen=max_colors=${settings.maxColors}:stats_mode=full[palette]"
    val gifStage = "[gifin][palette]paletteuse=dither=floyd_steinberg[out]"
    stages += baseStage
    stages += splitStage
    stages += paletteStage
    stages += gifStage
    return stages.joinToString(";")
}

/** Builds the complex filter graph responsible for blending two pre-rendered GIF streams. */
private fun buildBlendFilterGraph(
    blendMode: GifVisionBlendMode,
    opacity: Float,
    maxColors: Int = 256,
    matchDuration: Boolean = false,
    maxDuration: Float = 0f,
    matchDimensions: Boolean = false,
    targetWidth: Int = 0,
    targetHeight: Int = 0,
    scaleSecondary: Boolean = true
): String {
    val stages = mutableListOf<String>()
    
    // Process primary input
    if (matchDuration && maxDuration > 0f) {
        stages += "[0:v]setpts=PTS-STARTPTS,trim=duration=${maxDuration.toFfmpegString()}[primary_trimmed]"
    } else {
        stages += "[0:v]setpts=PTS-STARTPTS[primary_trimmed]"
    }
    
    // Process secondary input
    if (matchDuration && maxDuration > 0f) {
        stages += "[1:v]setpts=PTS-STARTPTS,trim=duration=${maxDuration.toFfmpegString()}[secondary_trimmed]"
    } else {
        stages += "[1:v]setpts=PTS-STARTPTS[secondary_trimmed]"
    }
    
    // Scale to match dimensions if needed
    if (matchDimensions && targetWidth > 0 && targetHeight > 0) {
        if (scaleSecondary) {
            // Scale secondary to match primary
            stages += "[primary_trimmed]copy[base]"
            stages += "[secondary_trimmed]scale=${targetWidth}:${targetHeight}:flags=lanczos[overlay]"
        } else {
            // Scale primary to match secondary
            stages += "[primary_trimmed]scale=${targetWidth}:${targetHeight}:flags=lanczos[base]"
            stages += "[secondary_trimmed]copy[overlay]"
        }
    } else {
        // No dimension matching needed
        stages += "[primary_trimmed]copy[base]"
        stages += "[secondary_trimmed]copy[overlay]"
    }
    
    // Blend the two streams
    stages += "[base][overlay]blend=all_mode=${blendMode.ffmpegKeyword()}:all_opacity=${opacity.coerceIn(0f, 1f).toFfmpegString()}[blended]"
    
    // Palette generation and application
    stages += "[blended]split[palin][gifin]"
    stages += "[palin]palettegen=max_colors=$maxColors:stats_mode=full[palette]"
    stages += "[gifin][palette]paletteuse=dither=floyd_steinberg[out]"
    
    return stages.joinToString(";")
}

private fun buildFrameRateFilter(frameRate: Float): String {
    val safe = frameRate.coerceIn(1f, 60f)
    return "fps=${safe.toFfmpegString()}"
}

private fun buildScaleFilter(resolutionPercent: Float): String {
    val safePercent = resolutionPercent.coerceIn(0.05f, 1f)
    return "scale='trunc(iw*${safePercent.toFfmpegString()}/2)*2:trunc(ih*${safePercent.toFfmpegString()}/2)*2:flags=lanczos'"
}

private fun buildEqFilter(settings: AdjustmentSettings): String? {
    val brightness = settings.brightness
    val contrast = settings.contrast
    val saturation = settings.saturation
    return if (brightness.isApproximately(0f) && contrast.isApproximately(1f) && saturation.isApproximately(1f)) {
        null
    } else {
        "eq=brightness=${brightness.toFfmpegString(precision = 3)}:contrast=${contrast.toFfmpegString(precision = 3)}:saturation=${saturation.toFfmpegString(precision = 3)}"
    }
}

private fun buildHueFilter(hue: Float): String? {
    return if (hue.isApproximately(0f)) {
        null
    } else {
        "hue=h=${hue.toFfmpegString()}"
    }
}

private fun buildSepiaFilter(amount: Float): String? {
    if (amount <= 0f) return null
    val clamped = amount.coerceIn(0f, 1f)
    val inv = 1f - clamped
    val rr = inv + 0.393f * clamped
    val rg = 0.769f * clamped
    val rb = 0.189f * clamped
    val gr = 0.349f * clamped
    val gg = inv + 0.686f * clamped
    val gb = 0.168f * clamped
    val br = 0.272f * clamped
    val bg = 0.534f * clamped
    val bb = inv + 0.131f * clamped
    return "colorchannelmixer=rr=${rr.toFfmpegString()}:rg=${rg.toFfmpegString()}:rb=${rb.toFfmpegString()}:gr=${gr.toFfmpegString()}:gg=${gg.toFfmpegString()}:gb=${gb.toFfmpegString()}:br=${br.toFfmpegString()}:bg=${bg.toFfmpegString()}:bb=${bb.toFfmpegString()}"
}

private fun buildColorBalanceFilter(settings: AdjustmentSettings): String? {
    if (settings.colorBalanceRed.isApproximately(1f) && settings.colorBalanceGreen.isApproximately(1f) && settings.colorBalanceBlue.isApproximately(1f)) {
        return null
    }
    return "colorchannelmixer=rr=${settings.colorBalanceRed.coerceAtLeast(0f).toFfmpegString()}:gg=${settings.colorBalanceGreen.coerceAtLeast(0f).toFfmpegString()}:bb=${settings.colorBalanceBlue.coerceAtLeast(0f).toFfmpegString()}"
}

/** Creates a pixelation effect using the pixelize filter. */
private fun buildPixellateFilter(amount: Float): String? {
    if (amount <= 0f) return null
    val safe = amount.coerceIn(0f, 50f)
    // Convert amount to block size: 1-50 -> 2-52 pixel blocks
    val blockSize = (2 + safe).roundToInt().coerceIn(2, 52)
    return "pixelize=width=$blockSize:height=$blockSize:mode=avg:planes=15"
}

/** Applies the timeline-aware hue rotation required by the Color Cycle control. */
private fun buildColorCycleFilter(speed: Float): String? {
    if (speed.isApproximately(0f)) return null
    // Speed is now in rotations per second (0-3 range)
    // Convert directly to degrees per second (1 rotation = 360 degrees)
    // Clamp to safe maximum to avoid FFmpeg crashes
    val degreesPerSecond = speed.coerceIn(0f, 3f) * 360f
    // Use h= instead of H= - the hue filter rotates from 0 degrees at the expression value
    return "hue=h='${degreesPerSecond.toFfmpegString()}*t':s=1"
}

/** Creates intense motion trail/blur effect using temporal mixing of multiple frames. */
private fun buildMotionTrailFilter(amount: Float): String? {
    if (amount <= 0f) return null
    val safe = amount.coerceIn(0f, 1f)
    
    // Calculate number of frames to mix based on intensity
    // 0.1 = 2 frames, 0.5 = 5 frames, 1.0 = 10 frames (extreme Bruce Lee style)
    val frameCount = (2 + (safe * 8f)).roundToInt().coerceIn(2, 10)
    
    // Build equal weights for all frames
    val weights = (1..frameCount).joinToString(" ") { "1" }
    
    return "tmix=frames=$frameCount:weights='$weights'"
}

/** Amplifies high-frequency detail without reintroducing halos. */
private fun buildSharpenFilter(amount: Float): String? {
    if (amount <= 0f) return null
    val safe = amount.coerceIn(0f, 1f)
    val sharpness = 1f + safe * 4f
    return "unsharp=luma_msize_x=5:luma_msize_y=5:luma_amount=${sharpness.toFfmpegString()}"
}

/** Detects edges and outputs white-on-black line art with optional brightness/contrast boost. */
private fun buildEdgeFilter(settings: AdjustmentSettings): String? {
    if (!settings.edgeDetectEnabled) return null
    
    val threshold = settings.edgeDetectThreshold.coerceIn(0f, 1f)
    val boost = settings.edgeDetectBoost.coerceIn(0f, 1f)
    
    // Map threshold: 0 = only strong edges (high/low thresholds), 1 = detect all edges (low thresholds)
    // Invert the mapping so higher slider = more edges
    val low = (0.05f + (1f - threshold) * 0.15f).coerceIn(0.05f, 0.2f)
    val high = (0.15f + (1f - threshold) * 0.25f).coerceIn(low + 0.05f, 0.4f)
    
    // Build the filter chain
    val filters = mutableListOf<String>()
    
    // Apply edge detection (wires mode = white edges on black background)
    filters += "edgedetect=mode=wires:low=${low.toFfmpegString()}:high=${high.toFfmpegString()}"
    
    // Apply boost if requested
    if (boost > 0f) {
        // Boost increases both brightness and contrast to make edges pop
        val brightnessBoost = boost * 0.3f  // Up to +0.3
        val contrastBoost = 1f + (boost * 0.5f)  // Up to 1.5x
        filters += "eq=brightness=${brightnessBoost.toFfmpegString(precision = 3)}:contrast=${contrastBoost.toFfmpegString(precision = 3)}"
    }
    
    return filters.joinToString(",")
}

/** Renders the text overlay if the user supplied copy. */
private fun buildDrawTextFilter(settings: AdjustmentSettings): String? {
    if (settings.textOverlay.isBlank()) return null
    
    // Find an available font on the device
    val fontPath = FfmpegKitGifProcessingCoordinator.findAvailableFont()
    if (fontPath == null) {
        android.util.Log.e("FFmpegKit", "No valid font file found for drawtext filter")
        return null
    }
    
    val escapedText = settings.textOverlay.escapeFfmpegText()
    // New range: minimum 50 (was 8), maximum 216 (was 72)
    val fontSize = settings.fontSizeSp.coerceIn(50, 216)
    
    // Simple color - just "white" or "black"
    val fontColor = when (settings.fontColorHex.lowercase()) {
        "black", "#000000", "#ff000000" -> "black"
        else -> "white"  // Default to white
    }
    
    android.util.Log.d("FFmpegKit", "Building drawtext filter:")
    android.util.Log.d("FFmpegKit", "  - Font: $fontPath")
    android.util.Log.d("FFmpegKit", "  - Text: '$escapedText'")
    android.util.Log.d("FFmpegKit", "  - Font size: $fontSize")
    android.util.Log.d("FFmpegKit", "  - Font color: $fontColor")
    
    // Build filter with size and color
    val filter = "drawtext=fontfile=$fontPath:text='$escapedText':fontsize=$fontSize:fontcolor=$fontColor:x=10:y=10"
    
    android.util.Log.d("FFmpegKit", "  - Filter: $filter")
    
    return filter
}

private suspend fun buildBlendCommand(
    request: LayerBlendRequest,
    output: File
): FfmpegCommand = buildBlendCommand(
    jobId = request.jobId,
    primaryPath = request.streamAPath,
    secondaryPath = request.streamBPath,
    blendMode = request.blendMode,
    opacity = request.opacity,
    output = output
)

private suspend fun buildMasterBlendCommand(
    request: MasterBlendRequest,
    output: File
): FfmpegCommand = buildBlendCommand(
    jobId = request.jobId,
    primaryPath = request.layerOnePath,
    secondaryPath = request.layerTwoPath,
    blendMode = request.blendMode,
    opacity = request.opacity,
    output = output
)

private fun Float.toFfmpegString(precision: Int = 2): String = String.format(Locale.US, "%1$.${precision}f", this)

private fun Float.isApproximately(target: Float, epsilon: Float = 0.0001f): Boolean = abs(this - target) < epsilon

private fun GifVisionBlendMode.ffmpegKeyword(): String = when (this) {
    GifVisionBlendMode.Normal -> "normal"
    GifVisionBlendMode.Multiply -> "multiply"
    GifVisionBlendMode.Screen -> "screen"
    GifVisionBlendMode.Overlay -> "overlay"
    GifVisionBlendMode.Darken -> "darken"
    GifVisionBlendMode.Lighten -> "lighten"
    GifVisionBlendMode.ColorDodge -> "dodge"
    GifVisionBlendMode.ColorBurn -> "burn"
    GifVisionBlendMode.HardLight -> "hardlight"
    GifVisionBlendMode.SoftLight -> "softlight"
    GifVisionBlendMode.Difference -> "difference"
    GifVisionBlendMode.Exclusion -> "exclusion"
    GifVisionBlendMode.Hue -> "hue"
    GifVisionBlendMode.Saturation -> "saturation"
    GifVisionBlendMode.Color -> "color"
    GifVisionBlendMode.Luminosity -> "luminosity"
}

private fun String.quoteIfNeeded(): String {
    if (isEmpty()) return this
    val alreadyWrapped = (startsWith("\"") && endsWith("\"")) || (startsWith("'") && endsWith("'"))
    return if (alreadyWrapped) {
        this
    } else if (any { it == ' ' || it == ';' || it == ':' }) {
        "\"$this\""
    } else {
        this
    }
}

private fun String.asFilterArgument(): String = "\"$this\""

private fun Statistics.progressRatio(durationMs: Float): Float {
    val timeMs = this.time.toFloat()
    return if (durationMs <= 0f) 0f else (timeMs / durationMs).coerceIn(0f, 1f)
}

private fun String.toFfmpegColor(): String {
    val colorInt = try {
        Color.parseColor(this)
    } catch (_: IllegalArgumentException) {
        Color.WHITE
    }
    val alpha = Color.alpha(colorInt) / 255f
    return String.format(Locale.US, "#%06X@%1$.2f", 0xFFFFFF and colorInt, alpha)
}

private fun String.escapeFfmpegText(): String =
    replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace(":", "\\:")
