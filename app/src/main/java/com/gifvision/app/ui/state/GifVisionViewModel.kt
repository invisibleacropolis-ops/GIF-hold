package com.gifvision.app.ui.state

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import com.gifvision.app.media.AndroidShareRepository
import com.gifvision.app.media.FfmpegKitGifProcessingCoordinator
import com.gifvision.app.media.GifProcessingCoordinator
import com.gifvision.app.media.GifProcessingNotificationAdapter
import com.gifvision.app.media.GifProcessingEvent
import com.gifvision.app.media.LayerBlendRequest
import com.gifvision.app.media.MediaRepository
import com.gifvision.app.media.MasterBlendRequest
import com.gifvision.app.media.ScopedStorageMediaRepository
import com.gifvision.app.media.ShareRepository
import com.gifvision.app.media.ShareRequest
import com.gifvision.app.media.ShareResult
import com.gifvision.app.media.StreamProcessingRequest
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.gifvision.app.ui.resources.LogCopy
import com.gifvision.app.ui.state.LogEntry
import com.gifvision.app.ui.state.LogSeverity
import com.gifvision.app.ui.state.validation.LayerBlendValidation
import com.gifvision.app.ui.state.validation.MasterBlendValidation
import com.gifvision.app.ui.state.validation.StreamValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Central point for managing GifVision's UI state and orchestrating FFmpeg jobs. The implementation
 * now understands validation, exposes derived flows for each layer, and dispatches work to the
 * injected repositories so the Compose layer stays declarative.
 */
class GifVisionViewModel(
    application: Application,
    notificationAdapter: GifProcessingNotificationAdapter = GifProcessingNotificationAdapter.Noop,
    private val mediaRepository: MediaRepository = ScopedStorageMediaRepository(application),
    private val processingCoordinator: GifProcessingCoordinator = FfmpegKitGifProcessingCoordinator(
        context = application,
        mediaRepository = mediaRepository,
        notificationAdapter = notificationAdapter
    ),
    private val shareRepository: ShareRepository = AndroidShareRepository(application)
) : AndroidViewModel(application) {

    private val contentResolver = application.contentResolver

    private val _uiState = MutableStateFlow(GifVisionUiState())

    /** Publicly exposed read-only state consumed by the Compose tree. */
    val uiState: StateFlow<GifVisionUiState> = _uiState.asStateFlow()

    private data class StreamKey(val layerId: Int, val stream: StreamSelection)

    private val _streamValidations = MutableStateFlow<Map<StreamKey, StreamValidation>>(emptyMap())
    private val _layerBlendValidations = MutableStateFlow<Map<Int, LayerBlendValidation>>(emptyMap())
    private val _masterBlendValidation = MutableStateFlow<MasterBlendValidation>(
        MasterBlendValidation.Blocked(listOf("Waiting for blended layers"))
    )

    /** Exposes whether the master blend button can be triggered. */
    val masterBlendValidation: StateFlow<MasterBlendValidation> = _masterBlendValidation.asStateFlow()

    private val _uiMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 1)

    /** Toast/one-off message feed consumed by the activity for user feedback. */
    val uiMessages: SharedFlow<UiMessage> = _uiMessages.asSharedFlow()

    data class UiMessage(val message: String, val isError: Boolean = false)

    init {
        _uiState.value.layers.forEach(::refreshValidationForLayer)
        refreshMasterBlendValidation()
    }

    /**
     * Handles the document picker result for a layer by harvesting metadata, caching a thumbnail,
     * and refreshing stream defaults. Heavy I/O work executes on Dispatchers.IO to keep Compose
     * responsive while the media subsystem inspects the selected URI.
     */
    fun importSourceClip(layerId: Int, uri: Uri) {
        viewModelScope.launch {
            val metadata = try {
                withContext(Dispatchers.IO) { loadClipMetadata(uri) }
            } catch (security: SecurityException) {
                appendLog(
                    layerId,
                    LogCopy.accessRevoked(uri, security.message),
                    LogSeverity.Error
                )
                clearLayerMedia(layerId)
                return@launch
            } catch (throwable: Throwable) {
                appendLog(
                    layerId,
                    "Failed to inspect clip: ${throwable.message ?: throwable.javaClass.simpleName}",
                    LogSeverity.Error
                )
                return@launch
            }
            val registered = try {
                mediaRepository.registerSourceClip(layerId, uri, metadata.displayName)
            } catch (security: SecurityException) {
                appendLog(
                    layerId,
                    LogCopy.storagePermissionRevoked(uri, security.message),
                    LogSeverity.Error
                )
                clearLayerMedia(layerId)
                return@launch
            }
            val thumbnailImage = metadata.thumbnailBitmap?.asImageBitmap()
            val clipState = SourceClip(
                uri = uri,
                displayName = metadata.displayName ?: registered.displayName,
                mimeType = metadata.mimeType,
                sizeBytes = metadata.sizeBytes,
                durationMs = metadata.durationMs,
                width = metadata.width,
                height = metadata.height,
                lastModified = metadata.lastModified,
                thumbnail = thumbnailImage
            )

            var clipWarnings: List<String> = emptyList()
            updateLayer(layerId) { layer ->
                val durationMs = clipState.durationMs ?: 0L
                val sanitizedDuration = durationMs.coerceAtLeast(0L)
                val (streamAAdjustments, warningsA) = adjustForClipDefaults(layer.streamA.adjustments, clipState)
                val (streamBAdjustments, warningsB) = adjustForClipDefaults(layer.streamB.adjustments, clipState)
                clipWarnings = (warningsA + warningsB).distinct()
                val updatedStreamA = layer.streamA.copy(
                    playbackPositionMs = 0L,
                    isPlaying = false,
                    trimStartMs = 0L,
                    trimEndMs = sanitizedDuration,
                    previewThumbnail = thumbnailImage,
                    adjustments = streamAAdjustments.copy(
                        clipDurationSeconds = (sanitizedDuration / 1000f)
                            .coerceAtLeast(0.5f)
                            .coerceAtMost(30f)
                    )
                )
                val updatedStreamB = layer.streamB.copy(
                    playbackPositionMs = 0L,
                    isPlaying = false,
                    trimStartMs = 0L,
                    trimEndMs = sanitizedDuration,
                    previewThumbnail = thumbnailImage,
                    adjustments = streamBAdjustments.copy(
                        clipDurationSeconds = (sanitizedDuration / 1000f)
                            .coerceAtLeast(0.5f)
                            .coerceAtMost(30f)
                    )
                )
                layer.copy(
                    sourceClip = clipState,
                    streamA = updatedStreamA,
                    streamB = updatedStreamB
                )
            }
            clipWarnings.forEach { appendLog(layerId, it, LogSeverity.Warning) }
            appendLog(layerId, "Imported ${clipState.displayName}")
        }
    }

    /**
     * Derived UI state per layer. The flow updates whenever the base state or validation status
     * changes, allowing screens to react to validation failures without bespoke listeners.
     */
    fun layerUiState(layerId: Int): StateFlow<LayerUiState?> {
        val initial = createLayerUiState(_uiState.value, layerId)
        return combine(uiState, _layerBlendValidations, _streamValidations) { state, blendValidations, streamValidations ->
            val layer = state.layers.firstOrNull { it.id == layerId } ?: return@combine null
            val activeStream = when (layer.activeStream) {
                StreamSelection.A -> layer.streamA
                StreamSelection.B -> layer.streamB
            }
            val streamValidation = streamValidations[StreamKey(layer.id, layer.activeStream)]
                ?: validateStream(layer, activeStream)
            val blendValidation = blendValidations[layer.id] ?: validateLayerBlend(layer)
            LayerUiState(layer = layer, activeStream = activeStream, streamValidation = streamValidation, layerBlendValidation = blendValidation)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
    }

    /** Returns validation feedback for a specific stream. */
    fun streamValidation(layerId: Int, stream: StreamSelection): StateFlow<StreamValidation> {
        val initial = createStreamValidationSnapshot(layerId, stream)
        return _streamValidations
            .map { validations -> validations[StreamKey(layerId, stream)] ?: createStreamValidationSnapshot(layerId, stream) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
    }

    /** Returns validation feedback for the per-layer blend card. */
    fun layerBlendValidation(layerId: Int): StateFlow<LayerBlendValidation> {
        val initial = createLayerBlendValidationSnapshot(layerId)
        return _layerBlendValidations
            .map { validations -> validations[layerId] ?: createLayerBlendValidationSnapshot(layerId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
    }

    /** Switches the currently visible layer tab (Layer 1 vs Layer 2). */
    fun selectLayer(index: Int) {
        _uiState.update { current ->
            current.copy(activeLayerIndex = index.coerceIn(current.layers.indices))
        }
    }

    /** Toggles the high-contrast theme override surfaced in the UI accessibility controls. */
    fun toggleHighContrast() {
        _uiState.update { state ->
            state.copy(isHighContrastEnabled = !state.isHighContrastEnabled)
        }
    }

    /** Explicit setter used when accessibility services request a particular contrast mode. */
    fun setHighContrast(enabled: Boolean) {
        _uiState.update { state ->
            if (state.isHighContrastEnabled == enabled) state else state.copy(isHighContrastEnabled = enabled)
        }
    }

    /** Updates the active stream radio toggle for the specified layer. */
    fun selectStream(layerId: Int, stream: StreamSelection) {
        updateLayer(layerId) { layer -> layer.copy(activeStream = stream) }
    }

    /** Applies a transformation to the adjustment set for the targeted stream. */
    fun updateAdjustments(
        layerId: Int,
        stream: StreamSelection,
        transformer: (AdjustmentSettings) -> AdjustmentSettings
    ) {
        updateLayer(layerId) { layer ->
            val currentStream = when (stream) {
                StreamSelection.A -> layer.streamA
                StreamSelection.B -> layer.streamB
            }
            val updatedSettings = transformer(currentStream.adjustments)
            val clipDurationMs = (updatedSettings.clipDurationSeconds * 1000).roundToLong().coerceAtLeast(0L)
            val maxDuration = layer.sourceClip?.durationMs ?: clipDurationMs
            val desiredEnd = (currentStream.trimStartMs + clipDurationMs).coerceAtMost(maxDuration)
            val actualDurationMs = (desiredEnd - currentStream.trimStartMs).coerceAtLeast(0L)
            val normalizedSettings = updatedSettings.copy(
                clipDurationSeconds = (actualDurationMs / 1000f)
                    .coerceAtLeast(0.5f)
                    .coerceAtMost(30f)
            )
            val updatedStream = currentStream.copy(
                adjustments = normalizedSettings,
                trimEndMs = desiredEnd,
                playbackPositionMs = currentStream.playbackPositionMs.coerceIn(
                    currentStream.trimStartMs,
                    desiredEnd
                )
            )
            when (stream) {
                StreamSelection.A -> layer.copy(streamA = updatedStream)
                StreamSelection.B -> layer.copy(streamB = updatedStream)
            }
        }
    }

    /** Updates the trim in/out points for the requested stream. */
    fun updateStreamTrim(
        layerId: Int,
        stream: StreamSelection,
        trimStartMs: Long,
        trimEndMs: Long
    ) {
        updateLayer(layerId) { layer ->
            val clipDuration = layer.sourceClip?.durationMs ?: Long.MAX_VALUE
            val start = trimStartMs.coerceIn(0L, clipDuration)
            val end = trimEndMs.coerceIn(start, clipDuration)
            val target = when (stream) {
                StreamSelection.A -> layer.streamA
                StreamSelection.B -> layer.streamB
            }
            val durationMs = (end - start).coerceAtLeast(0L)
            val updated = target.copy(
                trimStartMs = start,
                trimEndMs = end,
                playbackPositionMs = target.playbackPositionMs.coerceIn(start, end),
                adjustments = target.adjustments.copy(
                    clipDurationSeconds = (durationMs / 1000f)
                        .coerceAtLeast(0.5f)
                        .coerceAtMost(30f)
                )
            )
            when (stream) {
                StreamSelection.A -> layer.copy(streamA = updated)
                StreamSelection.B -> layer.copy(streamB = updated)
            }
        }
    }

    /** Records the latest playback head location reported by the preview player. */
    fun updateStreamPlaybackPosition(layerId: Int, stream: StreamSelection, positionMs: Long) {
        updateStream(layerId, stream) { current ->
            val clamped = positionMs.coerceIn(current.trimStartMs, current.trimEndMs)
            if (clamped == current.playbackPositionMs) current else current.copy(playbackPositionMs = clamped)
        }
    }

    /** Reflects whether the preview player is currently running for the targeted stream. */
    fun setStreamPlaying(layerId: Int, stream: StreamSelection, playing: Boolean) {
        updateStream(layerId, stream) { current ->
            if (current.isPlaying == playing) current else current.copy(isPlaying = playing)
        }
    }

    /** Stores the latest blend mode for the layer blend card. */
    fun updateLayerBlendMode(layerId: Int, mode: GifVisionBlendMode) {
        updateLayer(layerId) { layer ->
            layer.copy(blendState = layer.blendState.copy(mode = mode))
        }
    }

    /** Stores the opacity slider position for the layer blend card. */
    fun updateLayerBlendOpacity(layerId: Int, opacity: Float) {
        updateLayer(layerId) { layer ->
            layer.copy(
                blendState = layer.blendState.copy(opacity = opacity.coerceIn(0f, 1f))
            )
        }
    }

    /** Adjusts the master blend mode that merges the two layer outputs. */
    fun updateMasterBlendMode(mode: GifVisionBlendMode) {
        _uiState.update { state ->
            state.copy(masterBlend = state.masterBlend.copy(mode = mode))
        }
        refreshMasterBlendValidation()
    }

    /** Adjusts the master blend opacity slider. */
    fun updateMasterBlendOpacity(opacity: Float) {
        _uiState.update { state ->
            state.copy(
                masterBlend = state.masterBlend.copy(opacity = opacity.coerceIn(0f, 1f))
            )
        }
        refreshMasterBlendValidation()
    }

    /** Toggles whether the master blend card is interactable (based on render readiness). */
    fun setMasterBlendEnabled(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(masterBlend = state.masterBlend.copy(isEnabled = enabled))
        }
        refreshMasterBlendValidation()
    }

    /** Updates the share caption while recomputing live previews. */
    fun updateShareCaption(value: String) {
        updateShareSetup { share -> share.copy(caption = value) }
    }

    /** Updates the raw hashtag input and parsed list. */
    fun updateShareHashtags(value: String) {
        updateShareSetup { share ->
            val parsed = parseHashtags(value)
            share.copy(hashtagsInput = value, hashtags = parsed)
        }
    }

    /** Records the desired loop metadata for downstream save/share flows. */
    fun updateShareLoopMetadata(metadata: GifLoopMetadata) {
        updateShareSetup { share -> share.copy(loopMetadata = metadata) }
    }

    /** Adds a log line to either a layer-level or master-level FFmpeg log feed. */
    fun appendLog(layerId: Int?, message: String, severity: LogSeverity = LogSeverity.Info) {
        val entry = LogEntry(message = message, severity = severity)
        if (layerId == null) {
            _uiState.update { state ->
                state.copy(
                    masterBlend = state.masterBlend.copy(
                        ffmpegLogs = (state.masterBlend.ffmpegLogs + entry).takeLast(200)
                    )
                )
            }
        } else {
            updateLayer(layerId) { layer ->
                layer.copy(ffmpegLogs = (layer.ffmpegLogs + entry).takeLast(200))
            }
        }
        if (severity != LogSeverity.Info) {
            postMessage(message, isError = severity == LogSeverity.Error)
        }
    }

    /** Dispatches a render job for the requested stream after validating the state. */
    fun requestStreamRender(layerId: Int, stream: StreamSelection) {
        val state = _uiState.value
        val layer = state.layers.firstOrNull { it.id == layerId } ?: return
        val targetStream = when (stream) {
            StreamSelection.A -> layer.streamA
            StreamSelection.B -> layer.streamB
        }
        val validation = validateStream(layer, targetStream)
        if (validation is StreamValidation.Error) {
            logValidationFailure(layerId, validation.reasons)
            return
        }
        val sourceUri = layer.sourceClip?.uri
        if (sourceUri == null) {
            logValidationFailure(layerId, listOf("Source clip missing for ${layer.title}"))
            return
        }

        viewModelScope.launch {
            setStreamGenerating(layerId, stream, true)
            
            // Copy the content URI to a temporary file that FFmpeg can read
            val sourcePath = try {
                withContext(Dispatchers.IO) {
                    val tempFile = File(
                        getApplication<Application>().cacheDir,
                        "temp_input_${System.currentTimeMillis()}.mp4"
                    )
                    getApplication<Application>().contentResolver.openInputStream(sourceUri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile.absolutePath
                }
            } catch (e: Exception) {
                appendLog(layerId, "Failed to prepare source file: ${e.message}", LogSeverity.Error)
                setStreamGenerating(layerId, stream, false)
                return@launch
            }
            
            processingCoordinator.renderStream(
                StreamProcessingRequest(
                    layerId = layerId,
                    stream = stream,
                    sourcePath = sourcePath,
                    adjustments = targetStream.adjustments,
                    trimStartMs = targetStream.trimStartMs,
                    trimEndMs = targetStream.trimEndMs
                )
            ).collect { event ->
                when (event) {
                    is GifProcessingEvent.Started -> {
                        event.message?.let { appendLog(layerId, it) }
                    }

                    is GifProcessingEvent.Progress -> {
                        val percent = (event.progress * 100).roundToInt()
                        appendLog(layerId, event.message ?: LogCopy.jobProgress(event.jobId, percent))
                    }

                    is GifProcessingEvent.Completed -> {
                        val asset = mediaRepository.storeStreamOutput(layerId, stream, event.outputPath)
                        updateStream(layerId, stream) { current ->
                            current.copy(
                                generatedGifPath = asset.path,
                                isGenerating = false,
                                lastRenderTimestamp = asset.recordedAtEpochMillis
                            )
                        }
                        event.logs.forEach { appendLog(layerId, it) }
                        appendLog(layerId, LogCopy.jobComplete(event.jobId, asset.path))
                        
                        // Clean up temporary file
                        try {
                            File(sourcePath).delete()
                        } catch (e: Exception) {
                            // Ignore cleanup errors
                        }
                    }

                    is GifProcessingEvent.Failed -> {
                        updateStream(layerId, stream) { current -> current.copy(isGenerating = false) }
                        appendLog(
                            layerId,
                            "${event.jobId} failed: ${event.throwable.message}",
                            LogSeverity.Error
                        )
                        
                        // Clean up temporary file
                        try {
                            File(sourcePath).delete()
                        } catch (e: Exception) {
                            // Ignore cleanup errors
                        }
                    }

                    is GifProcessingEvent.Cancelled -> {
                        updateStream(layerId, stream) { current -> current.copy(isGenerating = false) }
                        appendLog(
                            layerId,
                            "${event.jobId} cancelled by user",
                            LogSeverity.Warning
                        )
                        
                        // Clean up temporary file
                        try {
                            File(sourcePath).delete()
                        } catch (e: Exception) {
                            // Ignore cleanup errors
                        }
                    }
                }
            }
        }
    }

    /** Dispatches a layer blend job once at least one stream render is available. */
    fun requestLayerBlend(layerId: Int) {
        val layer = _uiState.value.layers.firstOrNull { it.id == layerId } ?: return
        val validation = validateLayerBlend(layer)
        if (validation is LayerBlendValidation.Blocked) {
            logValidationFailure(layerId, validation.reasons)
            return
        }

        val streamAPath = layer.streamA.generatedGifPath
        val streamBPath = layer.streamB.generatedGifPath

        val hasStreamA = !streamAPath.isNullOrBlank()
        val hasStreamB = !streamBPath.isNullOrBlank()

        if (!hasStreamA && !hasStreamB) {
            appendLog(layerId, "Cannot blend - missing GIF files", LogSeverity.Error)
            return
        }

        if (hasStreamA && hasStreamB) {
            // Validate that both files actually exist
            val fileA = java.io.File(streamAPath)
            val fileB = java.io.File(streamBPath)

            if (!fileA.exists()) {
                appendLog(layerId, LogCopy.gifFileNotFound("Stream A", streamAPath), LogSeverity.Error)
                postMessage("Stream A GIF file missing. Try regenerating it.", isError = true)
                return
            }

            if (!fileB.exists()) {
                appendLog(layerId, LogCopy.gifFileNotFound("Stream B", streamBPath), LogSeverity.Error)
                postMessage("Stream B GIF file missing. Try regenerating it.", isError = true)
                return
            }

            appendLog(layerId, "Blending ${fileA.name} + ${fileB.name} with ${layer.blendState.mode.displayName} mode at ${layer.blendState.opacity} opacity")

            viewModelScope.launch {
                setLayerBlendGenerating(layerId, true)
                try {
                    processingCoordinator.blendLayer(
                        LayerBlendRequest(
                            layerId = layerId,
                            streamAPath = streamAPath,
                            streamBPath = streamBPath,
                            blendMode = layer.blendState.mode,
                            opacity = layer.blendState.opacity,
                            suggestedOutputPath = layer.blendState.blendedGifPath
                        )
                    ).collect { event ->
                        when (event) {
                            is GifProcessingEvent.Started -> event.message?.let { appendLog(layerId, it) }
                            is GifProcessingEvent.Progress -> {
                                val percent = (event.progress * 100).roundToInt()
                                appendLog(layerId, event.message ?: LogCopy.jobProgress(event.jobId, percent))
                            }
                            is GifProcessingEvent.Completed -> {
                                val asset = mediaRepository.storeLayerBlend(layerId, event.outputPath)
                                updateLayer(layerId) { current ->
                                    current.copy(
                                        blendState = current.blendState.copy(
                                            blendedGifPath = asset.path,
                                            isGenerating = false
                                        )
                                    )
                                }
                                event.logs.forEach { appendLog(layerId, it) }
                                appendLog(layerId, LogCopy.jobComplete(event.jobId, asset.path))
                                updateMasterBlendAvailability()
                            }
                            is GifProcessingEvent.Failed -> {
                                updateLayer(layerId) { current ->
                                    current.copy(blendState = current.blendState.copy(isGenerating = false))
                                }
                                appendLog(
                                    layerId,
                                    "${event.jobId} failed: ${event.throwable.message}",
                                    LogSeverity.Error
                                )
                                postMessage("Blend failed: ${event.throwable.message}", isError = true)
                            }
                            is GifProcessingEvent.Cancelled -> {
                                updateLayer(layerId) { current ->
                                    current.copy(blendState = current.blendState.copy(isGenerating = false))
                                }
                                appendLog(
                                    layerId,
                                    "${event.jobId} cancelled by user",
                                    LogSeverity.Warning
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Catch any uncaught exceptions to prevent app crashes
                    updateLayer(layerId) { current ->
                        current.copy(blendState = current.blendState.copy(isGenerating = false))
                    }
                    val message = e.message ?: "Unknown error during blend"
                    appendLog(layerId, LogCopy.blendError(message), LogSeverity.Error)
                    postMessage("Blend failed: $message", isError = true)
                }
            }
            return
        }

        val sourcePath = streamAPath ?: streamBPath
        val readyStreamLabel = if (hasStreamA) "Stream A" else "Stream B"
        val sourceFile = java.io.File(sourcePath!!)

        if (!sourceFile.exists()) {
            appendLog(layerId, LogCopy.gifFileNotFound(readyStreamLabel, sourcePath), LogSeverity.Error)
            postMessage("$readyStreamLabel GIF file missing. Try regenerating it.", isError = true)
            return
        }

        appendLog(layerId, "$readyStreamLabel ready – copying ${sourceFile.name} into the blend preview")

        viewModelScope.launch {
            setLayerBlendGenerating(layerId, true)
            try {
                val asset = mediaRepository.storeLayerBlend(layerId, sourcePath)
                updateLayer(layerId) { current ->
                    current.copy(
                        blendState = current.blendState.copy(
                            blendedGifPath = asset.path,
                            isGenerating = false
                        )
                    )
                }
                appendLog(layerId, "Blend preview now mirrors ${sourceFile.name}")
                updateMasterBlendAvailability()
            } catch (e: Exception) {
                updateLayer(layerId) { current ->
                    current.copy(blendState = current.blendState.copy(isGenerating = false))
                }
                val message = e.message ?: "Unknown error during blend"
                appendLog(layerId, LogCopy.blendError(message), LogSeverity.Error)
                postMessage("Blend failed: $message", isError = true)
            }
        }
    }

    /** Dispatches the master blend job after confirming both layers produced blends. */
    fun requestMasterBlend() {
        val state = _uiState.value
        val validation = _masterBlendValidation.value
        if (validation is MasterBlendValidation.Blocked) {
            logValidationFailure(layerId = null, validation.reasons)
            return
        }

        val layerOne = state.layers.getOrNull(0)?.blendState?.blendedGifPath ?: return
        val layerTwo = state.layers.getOrNull(1)?.blendState?.blendedGifPath ?: return

        viewModelScope.launch {
            setMasterBlendGenerating(true)
            processingCoordinator.mergeMaster(
                MasterBlendRequest(
                    layerOnePath = layerOne,
                    layerTwoPath = layerTwo,
                    blendMode = state.masterBlend.mode,
                    opacity = state.masterBlend.opacity,
                    suggestedOutputPath = state.masterBlend.masterGifPath
                )
            ).collect { event ->
                when (event) {
                    is GifProcessingEvent.Started -> event.message?.let { appendLog(null, it) }
                    is GifProcessingEvent.Progress -> {
                        val percent = (event.progress * 100).roundToInt()
                        appendLog(null, event.message ?: LogCopy.jobProgress(event.jobId, percent))
                    }
                    is GifProcessingEvent.Completed -> {
                        val asset = mediaRepository.storeMasterBlend(event.outputPath)
                        _uiState.update { current ->
                            current.copy(
                                masterBlend = current.masterBlend.copy(
                                    masterGifPath = asset.path,
                                    isGenerating = false
                                )
                            )
                        }
                        event.logs.forEach { appendLog(null, it) }
                        appendLog(null, LogCopy.jobComplete(event.jobId, asset.path))
                    }
                    is GifProcessingEvent.Failed -> {
                        setMasterBlendGenerating(false)
                        appendLog(
                            null,
                            "${event.jobId} failed: ${event.throwable.message}",
                            LogSeverity.Error
                        )
                    }
                    is GifProcessingEvent.Cancelled -> {
                        setMasterBlendGenerating(false)
                        appendLog(
                            null,
                            "${event.jobId} cancelled by user",
                            LogSeverity.Warning
                        )
                    }
                }
            }
            refreshMasterBlendValidation()
        }
    }

    /** Shares the master blend through the injected [ShareRepository]. */
    fun shareMasterOutput() {
        val master = _uiState.value.masterBlend
        val outputPath = master.masterGifPath
        if (outputPath.isNullOrBlank()) {
            appendLog(null, LogCopy.shareBlockedMasterNotReady(), LogSeverity.Warning)
            postMessage("Render the master blend before sharing.", isError = true)
            return
        }
        if (master.shareSetup.isPreparingShare) return
        updateShareSetup { share -> share.copy(isPreparingShare = true) }
        viewModelScope.launch {
            val request = ShareRequest(
                path = outputPath,
                displayName = deriveDisplayName(outputPath),
                caption = master.shareSetup.caption,
                hashtags = master.shareSetup.hashtags,
                loopMetadata = master.shareSetup.loopMetadata
            )
            when (val result = shareRepository.shareAsset(request)) {
                is ShareResult.Success -> {
                    appendLog(null, result.description)
                    postMessage(result.description)
                }
                is ShareResult.Failure -> {
                    appendLog(null, result.errorMessage, LogSeverity.Error)
                    postMessage(result.errorMessage, isError = true)
                }
            }
            updateShareSetup { share -> share.copy(isPreparingShare = false) }
        }
    }

    /** Persists the master blend into the user's public Downloads directory. */
    fun saveMasterOutput() {
        val master = _uiState.value.masterBlend
        val outputPath = master.masterGifPath
        if (outputPath.isNullOrBlank()) {
            appendLog(null, LogCopy.saveBlockedMasterNotReady(), LogSeverity.Warning)
            postMessage("Render the master blend before saving.", isError = true)
            return
        }
        viewModelScope.launch {
            runCatching {
                val destination = mediaRepository.exportToDownloads(outputPath, deriveDisplayName(outputPath))
                appendLog(null, "Master blend saved to ${destination}")
                postMessage("Saved GIF to Downloads")
            }.onFailure { throwable ->
                val message = throwable.message ?: "Unable to save GIF"
                appendLog(null, message, LogSeverity.Error)
                postMessage(message, isError = true)
            }
        }
    }

    /** Saves an individual stream GIF to the Downloads directory. */
    fun saveStreamOutput(layerId: Int, stream: StreamSelection) {
        val layer = _uiState.value.layers.firstOrNull { it.id == layerId } ?: return
        val targetStream = when (stream) {
            StreamSelection.A -> layer.streamA
            StreamSelection.B -> layer.streamB
        }
        val outputPath = targetStream.generatedGifPath
        if (outputPath.isNullOrBlank()) {
            appendLog(layerId, LogCopy.saveBlockedStreamNotRendered(stream.name), LogSeverity.Warning)
            postMessage("Generate Stream ${stream.name} before saving.", isError = true)
            return
        }
        viewModelScope.launch {
            runCatching {
                val displayName = "${layer.title.replace(" ", "_")}_Stream_${stream.name}"
                val destination = mediaRepository.exportToDownloads(outputPath, displayName)
                appendLog(layerId, "Stream ${stream.name} saved to ${destination}")
                postMessage("Saved Stream ${stream.name} GIF to Downloads")
            }.onFailure { throwable ->
                val message = throwable.message ?: "Unable to save Stream ${stream.name}"
                appendLog(layerId, message, LogSeverity.Error)
                postMessage(message, isError = true)
            }
        }
    }

    /** Helper that applies a mutation to a single layer identified by [layerId]. */
    private fun updateLayer(layerId: Int, transformer: (Layer) -> Layer) {
        var updatedLayer: Layer? = null
        _uiState.update { state ->
            val index = state.layers.indexOfFirst { it.id == layerId }
            if (index == -1) return@update state

            val mutated = transformer(state.layers[index])
            val updatedLayers = state.layers.toMutableList()
            updatedLayers[index] = mutated
            updatedLayer = mutated
            state.copy(layers = updatedLayers)
        }
        updatedLayer?.let {
            refreshValidationForLayer(it)
            updateMasterBlendAvailability()
        }
    }

    /** Clears the imported media for [layerId] while resetting dependent render state. */
    private fun clearLayerMedia(layerId: Int) {
        updateLayer(layerId) { layer ->
            layer.copy(
                sourceClip = null,
                streamA = layer.streamA.resetForRemoval(),
                streamB = layer.streamB.resetForRemoval(),
                blendState = layer.blendState.copy(
                    blendedGifPath = null,
                    isGenerating = false
                )
            )
        }
    }

    /** Resets a [Stream] to its pristine state after the backing clip has been removed. */
    private fun Stream.resetForRemoval(): Stream = copy(
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

    /** Helper that applies a mutation to a specific stream within a layer. */
    private fun updateStream(
        layerId: Int,
        stream: StreamSelection,
        transformer: (Stream) -> Stream
    ) {
        updateLayer(layerId) { layer ->
            when (stream) {
                StreamSelection.A -> layer.copy(streamA = transformer(layer.streamA))
                StreamSelection.B -> layer.copy(streamB = transformer(layer.streamB))
            }
        }
    }

    private fun setStreamGenerating(layerId: Int, stream: StreamSelection, generating: Boolean) {
        updateStream(layerId, stream) { current -> current.copy(isGenerating = generating) }
    }

    private fun setLayerBlendGenerating(layerId: Int, generating: Boolean) {
        updateLayer(layerId) { layer ->
            layer.copy(blendState = layer.blendState.copy(isGenerating = generating))
        }
    }

    private fun setMasterBlendGenerating(generating: Boolean) {
        _uiState.update { state ->
            state.copy(masterBlend = state.masterBlend.copy(isGenerating = generating))
        }
    }

    private fun updateShareSetup(transformer: (ShareSetupState) -> ShareSetupState) {
        _uiState.update { state ->
            val updatedShare = refreshSharePreviews(transformer(state.masterBlend.shareSetup))
            state.copy(masterBlend = state.masterBlend.copy(shareSetup = updatedShare))
        }
    }

    private fun refreshSharePreviews(share: ShareSetupState): ShareSetupState {
        val previews = SharePlatform.entries.map { platform ->
            buildPlatformPreview(platform, share.caption, share.hashtags, share.loopMetadata)
        }
        return share.copy(platformPreviews = previews)
    }

    private fun buildPlatformPreview(
        platform: SharePlatform,
        caption: String,
        hashtags: List<String>,
        metadata: GifLoopMetadata
    ): PlatformPreview {
        val normalizedCaption = caption.trim()
        val normalizedTags = hashtags.mapNotNull { tag ->
            val trimmed = tag.trim()
            if (trimmed.isEmpty()) null else if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        }
        val combined = listOf(
            normalizedCaption,
            normalizedTags.joinToString(separator = " ")
        ).filter { it.isNotBlank() }.joinToString(separator = "\n\n")
        val limit = platform.captionLimit
        val isTruncated = combined.length > limit
        val rendered = if (isTruncated && limit > 1) {
            combined.take(limit - 1).trimEnd() + "…"
        } else {
            combined
        }
        val remaining = limit - combined.length
        val overflowHashtags = (normalizedTags.size - platform.hashtagLimit).coerceAtLeast(0)
        val loopMessage = "${metadata.displayName} • ${platform.loopGuidance}"
        return PlatformPreview(
            platform = platform,
            renderedCaption = rendered,
            remainingCharacters = remaining,
            hashtagCount = normalizedTags.size,
            overflowHashtags = overflowHashtags,
            loopMessage = loopMessage,
            isCaptionTruncated = isTruncated
        )
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
            warnings += "Source file weighs ${Formatter.formatShortFileSize(getApplication<Application>(), sizeBytes)}; consider trimming to avoid oversized GIFs."
        }

        if (isLongClip) {
            warnings += "Clip longer than ${LONG_CLIP_WARNING_MS / 1000}s detected – long renders may be slowed."
        }

        return updated to warnings.distinct()
    }

    private fun parseHashtags(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        val tokens = input.split(',', ' ', '\n', '\t')
        val result = linkedSetOf<String>()
        tokens.forEach { raw ->
            val cleaned = raw.trim().removePrefix("#")
            if (cleaned.isNotEmpty()) {
                val sanitized = cleaned.replace(Regex("[^A-Za-z0-9_]"), "")
                if (sanitized.isNotEmpty()) {
                    result.add("#$sanitized")
                }
            }
        }
        return result.toList()
    }

    private fun deriveDisplayName(path: String): String {
        val fileName = File(path).nameWithoutExtension
        return if (fileName.isNotBlank()) fileName else "gifvision_master"
    }

    private fun postMessage(message: String, isError: Boolean = false) {
        if (!_uiMessages.tryEmit(UiMessage(message, isError))) {
            viewModelScope.launch { _uiMessages.emit(UiMessage(message, isError)) }
        }
    }

    private fun refreshValidationForLayer(layer: Layer) {
        val streamAValidation = validateStream(layer, layer.streamA)
        val streamBValidation = validateStream(layer, layer.streamB)
        val blendValidation = validateLayerBlend(layer)
        _streamValidations.update { current ->
            current.toMutableMap().apply {
                put(StreamKey(layer.id, StreamSelection.A), streamAValidation)
                put(StreamKey(layer.id, StreamSelection.B), streamBValidation)
            }
        }
        _layerBlendValidations.update { current ->
            current.toMutableMap().apply { put(layer.id, blendValidation) }
        }
        refreshMasterBlendValidation()
    }

    private fun refreshMasterBlendValidation() {
        val reasons = mutableListOf<String>()
        val state = _uiState.value
        if (state.layers.any { it.blendState.blendedGifPath.isNullOrBlank() }) {
            reasons += "Blend each layer before attempting the master mix"
        }
        if (state.masterBlend.opacity !in 0f..1f) {
            reasons += "Master blend opacity must remain between 0 and 1"
        }
        detectUnsupportedMasterBlend(state)?.let { reasons += it }
        _masterBlendValidation.value = if (reasons.isEmpty()) {
            MasterBlendValidation.Ready
        } else {
            MasterBlendValidation.Blocked(reasons)
        }
    }

    private fun updateMasterBlendAvailability() {
        val shouldEnable = _uiState.value.layers.all { !it.blendState.blendedGifPath.isNullOrBlank() }
        _uiState.update { state ->
            if (state.masterBlend.isEnabled == shouldEnable) {
                state
            } else {
                state.copy(masterBlend = state.masterBlend.copy(isEnabled = shouldEnable))
            }
        }
        refreshMasterBlendValidation()
    }

    /** Queries the system for metadata and a preview frame tied to [uri]. */
    private fun loadClipMetadata(uri: Uri): ClipMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        var lastModified: Long? = null
        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
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
            retriever.setDataSource(getApplication<Application>(), uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
            frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (ignored: RuntimeException) {
            // Some streams (e.g. remote URLs or unsupported codecs) may fail to provide frames.
        } finally {
            retriever.release()
        }

        val mimeType = contentResolver.getType(uri)

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

    private fun logValidationFailure(layerId: Int?, reasons: List<String>) {
        reasons.forEach { appendLog(layerId, "Validation: $it") }
    }

    private fun createLayerUiState(state: GifVisionUiState, layerId: Int): LayerUiState? {
        val layer = state.layers.firstOrNull { it.id == layerId } ?: return null
        val activeStream = when (layer.activeStream) {
            StreamSelection.A -> layer.streamA
            StreamSelection.B -> layer.streamB
        }
        val streamValidation = validateStream(layer, activeStream)
        val blendValidation = validateLayerBlend(layer)
        return LayerUiState(layer = layer, activeStream = activeStream, streamValidation = streamValidation, layerBlendValidation = blendValidation)
    }

    private fun createStreamValidationSnapshot(layerId: Int, stream: StreamSelection): StreamValidation {
        val layer = _uiState.value.layers.firstOrNull { it.id == layerId }
            ?: return StreamValidation.Error(listOf("Layer $layerId not available"))
        val streamState = when (stream) {
            StreamSelection.A -> layer.streamA
            StreamSelection.B -> layer.streamB
        }
        return validateStream(layer, streamState)
    }

    private fun createLayerBlendValidationSnapshot(layerId: Int): LayerBlendValidation {
        val layer = _uiState.value.layers.firstOrNull { it.id == layerId }
            ?: return LayerBlendValidation.Blocked(listOf("Layer $layerId not available"))
        return validateLayerBlend(layer)
    }

    private fun validateStream(layer: Layer, stream: Stream): StreamValidation {
        val errors = mutableListOf<String>()
        if (layer.sourceClip?.uri == null) {
            errors += "${layer.title}: select a source clip before rendering"
        }
        if (stream.adjustments.resolutionPercent <= 0f) {
            errors += "Resolution must be greater than 0%"
        }
        if (stream.adjustments.frameRate <= 0f) {
            errors += "Frame rate must be positive"
        }
        if (stream.adjustments.maxColors !in 2..256) {
            errors += "Color palette must be between 2 and 256 colors"
        }
        if (stream.adjustments.clipDurationSeconds <= 0f) {
            errors += "Clip duration must be positive"
        }
        return if (errors.isEmpty()) StreamValidation.Valid else StreamValidation.Error(errors)
    }

    private fun validateLayerBlend(layer: Layer): LayerBlendValidation {
        val errors = mutableListOf<String>()
        val streamAReady = !layer.streamA.generatedGifPath.isNullOrBlank()
        val streamBReady = !layer.streamB.generatedGifPath.isNullOrBlank()
        if (!streamAReady && !streamBReady) {
            errors += "Render Stream A or Stream B before blending"
        }
        if (layer.blendState.opacity !in 0f..1f) {
            errors += "Layer opacity must remain between 0 and 1"
        }
        detectUnsupportedLayerBlend(layer)?.let { errors += it }
        return if (errors.isEmpty()) LayerBlendValidation.Ready else LayerBlendValidation.Blocked(errors)
    }

    private fun detectUnsupportedLayerBlend(layer: Layer): String? {
        val colorSensitiveModes = setOf(
            GifVisionBlendMode.Color,
            GifVisionBlendMode.Hue,
            GifVisionBlendMode.Saturation,
            GifVisionBlendMode.Luminosity
        )
        val negateEnabled = layer.streamA.adjustments.negateColors || layer.streamB.adjustments.negateColors
        return if (layer.blendState.mode in colorSensitiveModes && negateEnabled) {
            "${layer.title}: ${layer.blendState.mode.displayName} is incompatible with the Negate Colors filter"
        } else {
            null
        }
    }

    private fun detectUnsupportedMasterBlend(state: GifVisionUiState): String? {
        val layerUsesDifference = state.layers.any { it.blendState.mode == GifVisionBlendMode.Difference }
        return if (layerUsesDifference && state.masterBlend.mode == GifVisionBlendMode.ColorDodge) {
            "Master blend Color Dodge cannot combine with Difference layer blends"
        } else {
            null
        }
    }

    /** Snapshot describing a single layer for Compose consumers. */
    data class LayerUiState(
        val layer: Layer,
        val activeStream: Stream,
        val streamValidation: StreamValidation,
        val layerBlendValidation: LayerBlendValidation
    )

    /**
     * Metadata payload emitted by [loadClipMetadata]. The view-model converts this object into the
     * immutable [SourceClip] consumed by Compose after converting the cached bitmap to
     * [androidx.compose.ui.graphics.ImageBitmap].
     */
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
        private const val HIGH_RES_PIXEL_THRESHOLD = 2_073_600 // 1080p worth of pixels
        private const val OVERSIZE_RESOLUTION = 0.6f
        private const val MAX_STREAM_FPS = 30f

        /**
         * Factory for creating GifVisionViewModel instances with default dependencies.
         * Required because the ViewModel has multiple constructor parameters that the
         * default AndroidViewModelFactory cannot handle via reflection.
         */
        val Factory: androidx.lifecycle.ViewModelProvider.Factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras
            ): T {
                val application = extras[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: throw IllegalArgumentException("Application not available in CreationExtras")
                return GifVisionViewModel(application) as T
            }
        }
    }
}
