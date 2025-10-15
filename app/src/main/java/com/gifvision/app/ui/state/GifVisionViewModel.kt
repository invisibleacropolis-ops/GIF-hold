package com.gifvision.app.ui.state

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gifvision.app.media.RenderJobRegistry
import com.gifvision.app.ui.resources.LogCopy
import com.gifvision.app.ui.state.coordinators.ClipImportOutcome
import com.gifvision.app.ui.state.coordinators.ClipImporter
import com.gifvision.app.ui.state.coordinators.RenderScheduler
import com.gifvision.app.ui.state.coordinators.ShareCoordinator
import com.gifvision.app.ui.state.messages.MessageCenter
import com.gifvision.app.ui.state.messages.UiMessage
import com.gifvision.app.ui.state.validation.LayerBlendValidation
import com.gifvision.app.ui.state.validation.MasterBlendValidation
import com.gifvision.app.ui.state.validation.StreamValidation
import com.gifvision.app.ui.state.validation.validateLayerBlend
import com.gifvision.app.ui.state.validation.validateMasterBlend
import com.gifvision.app.ui.state.validation.validateStream
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
import kotlin.math.roundToLong

/**
 * Central point for managing GifVision's UI state and orchestrating FFmpeg jobs. The implementation
 * now understands validation, exposes derived flows for each layer, and dispatches work to the
 * injected repositories so the Compose layer stays declarative.
 */
class GifVisionViewModel(
    application: Application,
    private val dependencies: GifVisionDependencies = GifVisionDependencies.default(application),
    private val messageCenter: MessageCenter = MessageCenter(),
    private val clipImporter: ClipImporter = ClipImporter(application, dependencies.mediaRepository),
    private val renderScheduler: RenderScheduler = RenderScheduler(
        application = application,
        processingCoordinator = dependencies.processingCoordinator,
        mediaRepository = dependencies.mediaRepository,
        messageCenter = messageCenter
    ),
    private val shareCoordinator: ShareCoordinator = ShareCoordinator(
        dependencies.mediaRepository,
        dependencies.shareRepository
    )
) : AndroidViewModel(application) {

    private val mediaRepository = dependencies.mediaRepository

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

    /** Toast/one-off message feed consumed by the activity for user feedback. */
    val uiMessages: SharedFlow<UiMessage> = messageCenter.messages

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
            val layer = _uiState.value.layers.firstOrNull { it.id == layerId } ?: return@launch
            when (val outcome = clipImporter.import(layer, uri)) {
                is ClipImportOutcome.Success -> {
                    updateLayer(layerId) { outcome.layer }
                    outcome.warnings.forEach { appendLog(layerId, it, LogSeverity.Warning) }
                    appendLog(layerId, "Imported ${outcome.importedName}")
                }
                is ClipImportOutcome.Failure -> {
                    appendLog(layerId, outcome.message, outcome.severity)
                    if (outcome.resetLayer) {
                        updateLayer(layerId) { current -> clipImporter.resetLayer(current) }
                    }
                }
            }
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
            messageCenter.post(viewModelScope, message, isError = severity == LogSeverity.Error)
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
        renderScheduler.requestStreamRender(
            scope = viewModelScope,
            layerId = layerId,
            streamSelection = stream,
            streamState = targetStream,
            sourceUri = sourceUri,
            onGeneratingChange = { generating -> setStreamGenerating(layerId, stream, generating) },
            onStreamCompleted = { path, recordedAt ->
                updateStream(layerId, stream) { current ->
                    current.copy(
                        generatedGifPath = path,
                        isGenerating = false,
                        lastRenderTimestamp = recordedAt
                    )
                }
            },
            onLog = { message, severity -> appendLog(layerId, message, severity) }
        )
    }

    /** Dispatches a layer blend job once at least one stream render is available. */
    fun requestLayerBlend(layerId: Int) {
        val layer = _uiState.value.layers.firstOrNull { it.id == layerId } ?: return
        val validation = validateLayerBlend(layer)
        if (validation is LayerBlendValidation.Blocked) {
            logValidationFailure(layerId, validation.reasons)
            return
        }
        renderScheduler.requestLayerBlend(
            scope = viewModelScope,
            layer = layer,
            onGeneratingChange = { generating -> setLayerBlendGenerating(layerId, generating) },
            onBlendSaved = { path ->
                updateLayer(layerId) { current ->
                    current.copy(
                        blendState = current.blendState.copy(blendedGifPath = path)
                    )
                }
            },
            onLog = { message, severity -> appendLog(layerId, message, severity) },
            onMasterAvailabilityChange = { updateMasterBlendAvailability() }
        )
    }

    /** Dispatches the master blend job after confirming both layers produced blends. */
    fun requestMasterBlend() {
        val state = _uiState.value
        val validation = _masterBlendValidation.value
        if (validation is MasterBlendValidation.Blocked) {
            logValidationFailure(layerId = null, validation.reasons)
            return
        }
        renderScheduler.requestMasterBlend(
            scope = viewModelScope,
            state = state,
            onGeneratingChange = { generating -> setMasterBlendGenerating(generating) },
            onMasterSaved = { path ->
                _uiState.update { current ->
                    current.copy(
                        masterBlend = current.masterBlend.copy(
                            masterGifPath = path,
                            isGenerating = false
                        )
                    )
                }
                refreshMasterBlendValidation()
            },
            onLog = { message, severity -> appendLog(null, message, severity) }
        )
    }

    fun cancelStreamRender(layerId: Int, stream: StreamSelection) {
        if (!renderScheduler.cancelStreamRender(layerId, stream)) {
            val jobId = RenderJobRegistry.streamRenderId(layerId, stream)
            appendLog(layerId, LogCopy.jobCancellationIgnored(jobId))
        }
    }

    fun cancelLayerBlend(layerId: Int) {
        val layer = _uiState.value.layers.firstOrNull { it.id == layerId } ?: return
        if (!renderScheduler.cancelLayerBlend(layer.id)) {
            val jobId = RenderJobRegistry.layerBlendId(layer.id, layer.blendState.mode)
            appendLog(layerId, LogCopy.jobCancellationIgnored(jobId))
        }
    }

    fun cancelMasterBlend() {
        val mode = _uiState.value.masterBlend.mode
        if (!renderScheduler.cancelMasterBlend()) {
            val jobId = RenderJobRegistry.masterBlendId(mode)
            appendLog(null, LogCopy.jobCancellationIgnored(jobId))
        }
    }

    /** Shares the master blend through the injected [ShareRepository]. */
    fun shareMasterOutput() {
        val master = _uiState.value.masterBlend
        val outputPath = master.masterGifPath
        if (outputPath.isNullOrBlank()) {
            appendLog(null, LogCopy.shareBlockedMasterNotReady(), LogSeverity.Warning)
            messageCenter.post(viewModelScope, "Render the master blend before sharing.", isError = true)
            return
        }
        if (master.shareSetup.isPreparingShare) return
        updateShareSetup { share -> share.copy(isPreparingShare = true) }
        viewModelScope.launch {
            val result = shareCoordinator.shareMasterBlend(master)
            appendLog(null, result.logMessage, result.severity)
            messageCenter.post(viewModelScope, result.userMessage, isError = result.isError)
            updateShareSetup { share -> share.copy(isPreparingShare = false) }
        }
    }

    /** Persists the master blend into the user's public Downloads directory. */
    fun saveMasterOutput() {
        val master = _uiState.value.masterBlend
        val outputPath = master.masterGifPath
        if (outputPath.isNullOrBlank()) {
            appendLog(null, LogCopy.saveBlockedMasterNotReady(), LogSeverity.Warning)
            messageCenter.post(viewModelScope, "Render the master blend before saving.", isError = true)
            return
        }
        viewModelScope.launch {
            val result = shareCoordinator.saveMasterBlend(master)
            appendLog(null, result.logMessage, result.severity)
            messageCenter.post(viewModelScope, result.userMessage, isError = result.isError)
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
            messageCenter.post(viewModelScope, "Generate Stream ${stream.name} before saving.", isError = true)
            return
        }
        viewModelScope.launch {
            val result = shareCoordinator.saveStream(layer, stream)
            appendLog(layerId, result.logMessage, result.severity)
            messageCenter.post(viewModelScope, result.userMessage, isError = result.isError)
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
        _masterBlendValidation.value = validateMasterBlend(_uiState.value)
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

    /** Snapshot describing a single layer for Compose consumers. */
    data class LayerUiState(
        val layer: Layer,
        val activeStream: Stream,
        val streamValidation: StreamValidation,
        val layerBlendValidation: LayerBlendValidation
    )

    companion object {
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
