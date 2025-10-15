package com.gifvision.app.ui.state.coordinators

import android.app.Application
import android.net.Uri
import com.gifvision.app.media.GifProcessingCoordinator
import com.gifvision.app.media.GifProcessingEvent
import com.gifvision.app.media.LayerBlendRequest
import com.gifvision.app.media.MediaRepository
import com.gifvision.app.media.RenderJobRegistry
import com.gifvision.app.media.MasterBlendRequest
import com.gifvision.app.media.StreamProcessingRequest
import com.gifvision.app.ui.resources.LogCopy
import com.gifvision.app.ui.state.GifVisionUiState
import com.gifvision.app.ui.state.Layer
import com.gifvision.app.ui.state.LogSeverity
import com.gifvision.app.ui.state.Stream
import com.gifvision.app.ui.state.StreamSelection
import com.gifvision.app.ui.state.messages.MessageCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlin.math.roundToInt

/** Coordinates render operations with the FFmpeg processing stack. */
internal class RenderScheduler(
    private val application: Application,
    private val processingCoordinator: GifProcessingCoordinator,
    private val mediaRepository: MediaRepository,
    private val messageCenter: MessageCenter
) {

    fun requestStreamRender(
        scope: CoroutineScope,
        layerId: Int,
        streamSelection: StreamSelection,
        streamState: Stream,
        sourceUri: Uri,
        onGeneratingChange: (Boolean) -> Unit,
        onStreamCompleted: (path: String, recordedAt: Long) -> Unit,
        onLog: (String, LogSeverity) -> Unit
    ) {
        val jobId = RenderJobRegistry.streamRenderId(layerId, streamSelection)
        var cancellationReported = false
        val job = scope.launch {
            onGeneratingChange(true)
            val sourcePath = try {
                copyUriToCache(sourceUri)
            } catch (throwable: Throwable) {
                onLog("Failed to prepare source file: ${throwable.message}", LogSeverity.Error)
                onGeneratingChange(false)
                return@launch
            }

            try {
                processingCoordinator.renderStream(
                    StreamProcessingRequest(
                        layerId = layerId,
                        stream = streamSelection,
                        sourcePath = sourcePath,
                        adjustments = streamState.adjustments,
                        trimStartMs = streamState.trimStartMs,
                        trimEndMs = streamState.trimEndMs,
                        jobId = jobId
                    )
                ).collect { event ->
                    when (event) {
                        is GifProcessingEvent.Started -> event.message?.let { onLog(it, LogSeverity.Info) }
                        is GifProcessingEvent.Progress -> {
                            val percent = (event.progress * 100).roundToInt()
                            val message = event.message ?: LogCopy.jobProgress(event.jobId, percent)
                            onLog(message, LogSeverity.Info)
                        }
                        is GifProcessingEvent.Completed -> {
                            val asset = mediaRepository.storeStreamOutput(layerId, streamSelection, event.outputPath)
                            onStreamCompleted(asset.path, asset.recordedAtEpochMillis)
                            event.logs.forEach { onLog(it, LogSeverity.Info) }
                            onLog(LogCopy.jobComplete(event.jobId, asset.path), LogSeverity.Info)
                            onGeneratingChange(false)
                        }
                        is GifProcessingEvent.Failed -> {
                            onGeneratingChange(false)
                            onLog("${event.jobId} failed: ${event.throwable.message}", LogSeverity.Error)
                        }
                        is GifProcessingEvent.Cancelled -> {
                            cancellationReported = true
                            onGeneratingChange(false)
                            onLog("${event.jobId} cancelled by user", LogSeverity.Warning)
                        }
                    }
                }
            } finally {
                runCatching { File(sourcePath).delete() }
            }
        }
        registerJob(
            key = RenderJobKey.Stream(layerId, streamSelection),
            jobId = jobId,
            job = job,
            onGeneratingChange = onGeneratingChange,
            onLog = onLog,
            wasCancellationReported = { cancellationReported }
        )
    }

    fun requestLayerBlend(
        scope: CoroutineScope,
        layer: Layer,
        onGeneratingChange: (Boolean) -> Unit,
        onBlendSaved: (String) -> Unit,
        onLog: (String, LogSeverity) -> Unit,
        onMasterAvailabilityChange: () -> Unit
    ) {
        val streamAPath = layer.streamA.generatedGifPath
        val streamBPath = layer.streamB.generatedGifPath

        val hasStreamA = !streamAPath.isNullOrBlank()
        val hasStreamB = !streamBPath.isNullOrBlank()

        if (!hasStreamA && !hasStreamB) {
            onLog("Cannot blend - missing GIF files", LogSeverity.Error)
            return
        }

        if (hasStreamA && hasStreamB) {
            val fileA = File(streamAPath!!)
            val fileB = File(streamBPath!!)
            if (!fileA.exists()) {
                onLog(LogCopy.gifFileNotFound("Stream A", streamAPath), LogSeverity.Error)
                messageCenter.post(scope, "Stream A GIF file missing. Try regenerating it.", isError = true)
                return
            }
            if (!fileB.exists()) {
                onLog(LogCopy.gifFileNotFound("Stream B", streamBPath), LogSeverity.Error)
                messageCenter.post(scope, "Stream B GIF file missing. Try regenerating it.", isError = true)
                return
            }

            onLog(
                "Blending ${fileA.name} + ${fileB.name} with ${layer.blendState.mode.displayName} mode at ${layer.blendState.opacity} opacity",
                LogSeverity.Info
            )

            val jobId = RenderJobRegistry.layerBlendId(layer.id, layer.blendState.mode)
            var cancellationReported = false
            val job = scope.launch {
                onGeneratingChange(true)
                try {
                    processingCoordinator.blendLayer(
                        LayerBlendRequest(
                            layerId = layer.id,
                            streamAPath = streamAPath,
                            streamBPath = streamBPath,
                            blendMode = layer.blendState.mode,
                            opacity = layer.blendState.opacity,
                            suggestedOutputPath = layer.blendState.blendedGifPath,
                            jobId = jobId
                        )
                    ).collect { event ->
                        when (event) {
                            is GifProcessingEvent.Started -> event.message?.let { onLog(it, LogSeverity.Info) }
                            is GifProcessingEvent.Progress -> {
                                val percent = (event.progress * 100).roundToInt()
                                onLog(event.message ?: LogCopy.jobProgress(event.jobId, percent), LogSeverity.Info)
                            }
                            is GifProcessingEvent.Completed -> {
                                val asset = mediaRepository.storeLayerBlend(layer.id, event.outputPath)
                                onBlendSaved(asset.path)
                                event.logs.forEach { onLog(it, LogSeverity.Info) }
                                onLog(LogCopy.jobComplete(event.jobId, asset.path), LogSeverity.Info)
                                onGeneratingChange(false)
                                onMasterAvailabilityChange()
                            }
                            is GifProcessingEvent.Failed -> {
                                onGeneratingChange(false)
                                onLog("${event.jobId} failed: ${event.throwable.message}", LogSeverity.Error)
                                messageCenter.post(scope, "Blend failed: ${event.throwable.message}", isError = true)
                            }
                            is GifProcessingEvent.Cancelled -> {
                                cancellationReported = true
                                onGeneratingChange(false)
                                onLog("${event.jobId} cancelled by user", LogSeverity.Warning)
                            }
                        }
                    }
                } catch (throwable: Throwable) {
                    onGeneratingChange(false)
                    val message = throwable.message ?: "Unknown error during blend"
                    onLog(LogCopy.blendError(message), LogSeverity.Error)
                    messageCenter.post(scope, "Blend failed: $message", isError = true)
                }
            }
            registerJob(
                key = RenderJobKey.LayerBlend(layer.id),
                jobId = jobId,
                job = job,
                onGeneratingChange = onGeneratingChange,
                onLog = onLog,
                wasCancellationReported = { cancellationReported }
            )
            return
        }

        val sourcePath = streamAPath ?: streamBPath
        val readyStreamLabel = if (hasStreamA) "Stream A" else "Stream B"
        val sourceFile = File(sourcePath!!)
        if (!sourceFile.exists()) {
            onLog(LogCopy.gifFileNotFound(readyStreamLabel, sourcePath), LogSeverity.Error)
            messageCenter.post(scope, "$readyStreamLabel GIF file missing. Try regenerating it.", isError = true)
            return
        }

        onLog("$readyStreamLabel ready – copying ${sourceFile.name} into the blend preview", LogSeverity.Info)

        val jobId = RenderJobRegistry.layerBlendId(layer.id, layer.blendState.mode)
        var cancellationReported = false
        val job = scope.launch {
            onGeneratingChange(true)
            runCatching {
                mediaRepository.storeLayerBlend(layer.id, sourcePath)
            }.onSuccess { asset ->
                onBlendSaved(asset.path)
                onLog("Blend preview now mirrors ${sourceFile.name}", LogSeverity.Info)
                onGeneratingChange(false)
                onMasterAvailabilityChange()
            }.onFailure { throwable ->
                onGeneratingChange(false)
                val message = throwable.message ?: "Unknown error during blend"
                onLog(LogCopy.blendError(message), LogSeverity.Error)
                messageCenter.post(scope, "Blend failed: $message", isError = true)
            }
        }
        registerJob(
            key = RenderJobKey.LayerBlend(layer.id),
            jobId = jobId,
            job = job,
            onGeneratingChange = onGeneratingChange,
            onLog = onLog,
            wasCancellationReported = { cancellationReported }
        )
    }

    fun requestMasterBlend(
        scope: CoroutineScope,
        state: GifVisionUiState,
        onGeneratingChange: (Boolean) -> Unit,
        onMasterSaved: (String) -> Unit,
        onLog: (String, LogSeverity) -> Unit
    ) {
        val layerOne = state.layers.getOrNull(0)?.blendState?.blendedGifPath ?: return
        val layerTwo = state.layers.getOrNull(1)?.blendState?.blendedGifPath ?: return

        val jobId = RenderJobRegistry.masterBlendId(state.masterBlend.mode)
        var cancellationReported = false
        val job = scope.launch {
            onGeneratingChange(true)
            processingCoordinator.mergeMaster(
                MasterBlendRequest(
                    layerOnePath = layerOne,
                    layerTwoPath = layerTwo,
                    blendMode = state.masterBlend.mode,
                    opacity = state.masterBlend.opacity,
                    suggestedOutputPath = state.masterBlend.masterGifPath,
                    jobId = jobId
                )
            ).collect { event ->
                when (event) {
                    is GifProcessingEvent.Started -> event.message?.let { onLog(it, LogSeverity.Info) }
                    is GifProcessingEvent.Progress -> {
                        val percent = (event.progress * 100).roundToInt()
                        onLog(event.message ?: LogCopy.jobProgress(event.jobId, percent), LogSeverity.Info)
                    }
                    is GifProcessingEvent.Completed -> {
                        val asset = mediaRepository.storeMasterBlend(event.outputPath)
                        onMasterSaved(asset.path)
                        event.logs.forEach { onLog(it, LogSeverity.Info) }
                        onLog(LogCopy.jobComplete(event.jobId, asset.path), LogSeverity.Info)
                        onGeneratingChange(false)
                    }
                    is GifProcessingEvent.Failed -> {
                        onGeneratingChange(false)
                        onLog("${event.jobId} failed: ${event.throwable.message}", LogSeverity.Error)
                    }
                    is GifProcessingEvent.Cancelled -> {
                        cancellationReported = true
                        onGeneratingChange(false)
                        onLog("${event.jobId} cancelled by user", LogSeverity.Warning)
                    }
                }
            }
        }
        registerJob(
            key = RenderJobKey.MasterBlend,
            jobId = jobId,
            job = job,
            onGeneratingChange = onGeneratingChange,
            onLog = onLog,
            wasCancellationReported = { cancellationReported }
        )
    }

    fun cancelStreamRender(layerId: Int, stream: StreamSelection): Boolean {
        return cancelJob(RenderJobKey.Stream(layerId, stream))
    }

    fun cancelLayerBlend(layerId: Int): Boolean {
        return cancelJob(RenderJobKey.LayerBlend(layerId))
    }

    fun cancelMasterBlend(): Boolean {
        return cancelJob(RenderJobKey.MasterBlend)
    }

    private fun cancelJob(key: RenderJobKey): Boolean {
        val tracked = synchronized(activeJobs) { activeJobs[key] }
        tracked?.let {
            it.job.cancel()
            return true
        }
        return false
    }

    private fun registerJob(
        key: RenderJobKey,
        jobId: String,
        job: Job,
        onGeneratingChange: (Boolean) -> Unit,
        onLog: (String, LogSeverity) -> Unit,
        wasCancellationReported: () -> Boolean
    ) {
        synchronized(activeJobs) {
            activeJobs[key]?.job?.cancel()
            activeJobs[key] = ActiveJob(jobId, job, wasCancellationReported)
        }
        job.invokeOnCompletion { throwable ->
            synchronized(activeJobs) {
                val tracked = activeJobs[key]
                if (tracked?.job === job) {
                    activeJobs.remove(key)
                }
            }
            if (throwable is CancellationException && !wasCancellationReported()) {
                onGeneratingChange(false)
                onLog(LogCopy.jobCancelled(jobId), LogSeverity.Warning)
            }
        }
    }

    private data class ActiveJob(
        val jobId: String,
        val job: Job,
        val wasCancellationReported: () -> Boolean
    )

    private sealed interface RenderJobKey {
        data class Stream(val layerId: Int, val stream: StreamSelection) : RenderJobKey
        data class LayerBlend(val layerId: Int) : RenderJobKey
        data object MasterBlend : RenderJobKey
    }

    private val activeJobs = mutableMapOf<RenderJobKey, ActiveJob>()

    private suspend fun copyUriToCache(uri: Uri): String = withContext(Dispatchers.IO) {
        val tempFile = File(application.cacheDir, "temp_input_${System.currentTimeMillis()}.mp4")
        application.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        tempFile.absolutePath
    }
}
