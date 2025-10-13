package com.gifvision.app.ui.state

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Domain + state models for GifVision. The goal is to expose a single source of truth that UI,
 * persistence, and the rendering pipeline can share without type mapping. Each data class below
 * corresponds to a concept called out in the user manual (streams, layers, adjustments, blend
 * cards) and includes every control the product ships with.
 */

/**
 * Identifies which of the two stream slots (A or B) within a layer is currently being configured
 * by the user. The manual repeatedly refers to "Stream A" and "Stream B", so the enum mirrors that
 * terminology for clarity.
 */
enum class StreamSelection {
    A,
    B
}

/**
 * Enumerates the blend modes supported both at the per-layer level (Stream A blended with
 * Stream B) and the master mix level (Layer 1 blended with Layer 2). The strings match the
 * labels in the product manual so UI binding can remain straightforward.
 */
enum class GifVisionBlendMode(val displayName: String) {
    Normal("Normal"),
    Multiply("Multiply"),
    Screen("Screen"),
    Overlay("Overlay"),
    Darken("Darken"),
    Lighten("Lighten"),
    ColorDodge("Color Dodge"),
    ColorBurn("Color Burn"),
    HardLight("Hard Light"),
    SoftLight("Soft Light"),
    Difference("Difference"),
    Exclusion("Exclusion"),
    Hue("Hue"),
    Saturation("Saturation"),
    Color("Color"),
    Luminosity("Luminosity")
}

/**
 * Enumerates the looping metadata stamped onto the exported GIF. Each value mirrors the social
 * platform configuration options surfaced in the product brief so share requests can express user
 * intent alongside the binary payload.
 */
enum class GifLoopMetadata(val displayName: String, val description: String) {
    Auto("Auto", "Let receiving apps pick the default loop behaviour."),
    LoopForever("Loop forever", "Flag the GIF as infinitely looping."),
    PlayOnce("Play once", "Signal that the animation should stop after a single iteration."),
    Bounce("Bounce", "Request palindromic playback when the destination supports it.")
}

/**
 * Severity levels exposed by the FFmpeg log stream. Mapping severities up front lets UI surfaces
 * tint entries consistently while also enabling the view-model to fan out toast notifications for
 * warnings and errors without re-parsing raw strings downstream.
 */
enum class LogSeverity {
    Info,
    Warning,
    Error
}

/**
 * Structured representation of an FFmpeg log line. Capturing the originating timestamp keeps the
 * entries sortable while also providing human friendly copy/share payloads from the diagnostics
 * panel.
 */
@Immutable
data class LogEntry(
    val message: String,
    val severity: LogSeverity = LogSeverity.Info,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * Target destinations used for live share previews. Limits loosely reflect public documentation for
 * each platform so the UI can surface truncation and tag guidance while the user edits copy.
 */
enum class SharePlatform(
    val displayName: String,
    val captionLimit: Int,
    val hashtagLimit: Int,
    val loopGuidance: String
) {
    Instagram(
        displayName = "Instagram",
        captionLimit = 2_200,
        hashtagLimit = 30,
        loopGuidance = "Loops forever on feed; Stories respect Auto/Bounce."
    ),
    TikTok(
        displayName = "TikTok",
        captionLimit = 2_200,
        hashtagLimit = 30,
        loopGuidance = "Auto honours TikTok's intelligent looping recommendations."
    ),
    X(
        displayName = "X",
        captionLimit = 280,
        hashtagLimit = 25,
        loopGuidance = "Short loops perform best; Play Once disables the animated preview."
    )
}

/**
 * Captures how a share payload would appear on a specific [SharePlatform]. The rendered caption,
 * hashtag counts, and loop guidance are derived from the user's selections so the Compose panel can
 * expose destination-specific affordances.
 */
@Immutable
data class PlatformPreview(
    val platform: SharePlatform,
    val renderedCaption: String,
    val remainingCharacters: Int,
    val hashtagCount: Int,
    val overflowHashtags: Int,
    val loopMessage: String,
    val isCaptionTruncated: Boolean
)

/**
 * Share setup surface that lives alongside the master blend. The state records user-authored copy,
 * parsed hashtags, desired loop metadata, and precalculated previews for Instagram, TikTok, and X.
 * Keeping the state immutable allows the view-model to emit atomic updates into Compose.
 */
@Immutable
data class ShareSetupState(
    val caption: String = "",
    val hashtagsInput: String = "",
    val hashtags: List<String> = emptyList(),
    val loopMetadata: GifLoopMetadata = GifLoopMetadata.Auto,
    val platformPreviews: List<PlatformPreview> = SharePlatform.entries.map { platform ->
        PlatformPreview(
            platform = platform,
            renderedCaption = "",
            remainingCharacters = platform.captionLimit,
            hashtagCount = 0,
            overflowHashtags = 0,
            loopMessage = "${GifLoopMetadata.Auto.displayName} • ${platform.loopGuidance}",
            isCaptionTruncated = false
        )
    },
    val isPreparingShare: Boolean = false
)

/**
 * Captures every tweakable setting documented in the adjustments panel. The property list mirrors
 * the user manual one-to-one so feature teams can connect Compose controls, persistence, and the
 * FFmpeg flag builders without hunting across multiple models.
 */
@Immutable
data class AdjustmentSettings(
    val resolutionPercent: Float = 1.0f,
    val maxColors: Int = 256,
    val frameRate: Float = 15f,
    val clipDurationSeconds: Float = 3f,
    val textOverlay: String = "",
    val fontSizeSp: Int = 54,  // Better default size (was 18)
    val fontColorHex: String = "white",  // Simple "white" or "black"
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val hue: Float = 0f,
    val sepia: Float = 0f,
    val colorBalanceRed: Float = 1f,
    val colorBalanceGreen: Float = 1f,
    val colorBalanceBlue: Float = 1f,
    val spectrumPulseIntensity: Float = 0f,
    val colorCycleSpeed: Float = 0f,
    val motionTrails: Float = 0f,
    val sharpen: Float = 0f,
    val pixellate: Float = 0f,
    val edgeDetectEnabled: Boolean = false,
    val edgeDetectThreshold: Float = 0.1f,
    val edgeDetectBoost: Float = 0f,
    val negateColors: Boolean = false,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false
)

/**
 * Represents the rendering status of an individual stream (A or B) within a layer. Besides the
 * adjustment payload it keeps track of preview media, generated GIFs, and in-flight render state so
 * WorkManager integrations have a landing zone.
 */
@Immutable
data class Stream(
    val stream: StreamSelection,
    val adjustments: AdjustmentSettings = AdjustmentSettings(),
    val sourcePreviewPath: String? = null,
    val generatedGifPath: String? = null,
    val isGenerating: Boolean = false,
    val lastRenderTimestamp: Long? = null,
    val playbackPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val previewThumbnail: ImageBitmap? = null
)

/**
 * Tracks the blend configuration between Stream A and Stream B of a single layer. The data mirrors
 * the blend preview card with mode + opacity, render status, and the latest blended asset URI.
 */
@Immutable
data class BlendConfig(
    val mode: GifVisionBlendMode = GifVisionBlendMode.Normal,
    val opacity: Float = 1f,
    val blendedGifPath: String? = null,
    val isGenerating: Boolean = false
)

/**
 * Consolidates all UI-relevant metadata for one layer (Layer 1 or Layer 2). Layers wrap the two
 * streams, the per-layer blend configuration, source metadata, and rolling FFmpeg logs so the UI
 * can bind without additional lookups.
 */
@Immutable
data class Layer(
    val id: Int,
    val title: String,
    val sourceClip: SourceClip? = null,
    val streamA: Stream = Stream(stream = StreamSelection.A),
    val streamB: Stream = Stream(stream = StreamSelection.B),
    val activeStream: StreamSelection = StreamSelection.A,
    val blendState: BlendConfig = BlendConfig(),
    val ffmpegLogs: List<LogEntry> = emptyList()
)

/**
 * Rich metadata for the imported source clip. Having all attributes on hand allows the UI to
 * present friendly summaries and prevents repeated queries against the system content resolver
 * when different streams request thumbnails or duration data.
 */
@Immutable
data class SourceClip(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val lastModified: Long?,
    val thumbnail: ImageBitmap? = null
)

/**
 * State for the final master blend card which combines the outputs of Layer 1 and Layer 2.
 * Mirrors the manual's "Master Blend" section, including the enablement gate that only unlocks
 * after both layers finish rendering their blends.
 */
@Immutable
data class MasterBlendConfig(
    val mode: GifVisionBlendMode = GifVisionBlendMode.Normal,
    val opacity: Float = 1f,
    val masterGifPath: String? = null,
    val isEnabled: Boolean = false,
    val isGenerating: Boolean = false,
    val ffmpegLogs: List<LogEntry> = emptyList(),
    val shareSetup: ShareSetupState = ShareSetupState()
)

/**
 * Top-level snapshot consumed by the composable tree. The default constructor prepares two empty
 * layers so previews render without null guards. Downstream features can treat this as both the UI
 * state holder and the domain representation of a project session.
 */
@Immutable
data class GifVisionUiState(
    val layers: List<Layer> = listOf(
        Layer(id = 1, title = "Layer 1"),
        Layer(id = 2, title = "Layer 2")
    ),
    val masterBlend: MasterBlendConfig = MasterBlendConfig(),
    val activeLayerIndex: Int = 0,
    val isHighContrastEnabled: Boolean = false
) {
    /** Convenience accessor for whichever layer is currently open in the UI. */
    val activeLayer: Layer
        get() = layers[activeLayerIndex.coerceIn(layers.indices)]
}
