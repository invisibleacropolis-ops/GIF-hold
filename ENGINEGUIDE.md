# GifVision Engineering Guide

## 1. Project Overview
GifVision is an Android-only Jetpack Compose application that follows the workflow described in `USERMANUAL.txt`. The current implementation delivers a production-ready skeleton that wires together the navigation shell, state containers, UI scaffolding, and dependency graph required to implement timeline-aware GIF rendering with FFmpegKit. Media playback, capture, and rendering hooks are intentionally stubbed with loggers so engineers can plug in domain logic without disrupting the Compose surface.

## 2. Module Layout
```
app/
  └── src/main/java/com/gifvision/app/
      ├── media/                        # Service + repository contracts
      │   ├── GifProcessingCoordinator.kt
      │   ├── MediaRepository.kt
      │   └── ShareRepository.kt
      ├── MainActivity.kt                # Entry point + Compose setup
      ├── navigation/                    # Route constants
      ├── ui/                            # Screen-level composables
      │   ├── GifVisionApp.kt            # Scaffold + NavHost + bottom bar
      │   ├── LayerScreen.kt             # Layer 1 / Layer 2 UI
      │   └── MasterBlendScreen.kt       # Final blend controls
      └── ui/state/                      # State containers + ViewModel
          ├── GifVisionState.kt
          └── GifVisionViewModel.kt
```
The `ui/theme` package houses Material theming primitives. Resource overrides and manifest capabilities sit in `app/src/main/res` and `app/src/main/AndroidManifest.xml` respectively.

## 3. Build & Tooling Requirements
* **Android SDK** – Gradle requires `sdk.dir` in `local.properties` or `ANDROID_HOME` to locate the Android SDK during `assembleDebug`. The CI skeleton assumes API 36 (Android 15) per `compileSdk`/`targetSdk`.
* **Java 17** – FFmpegKit and Media3 demand Java 17 bytecode. `compileOptions`/`kotlinOptions` are already aligned to Java 17.
* **Gradle 8.13 / AGP 8.1.3** – Managed through the Gradle wrapper and `libs.versions.toml`.

Running the build:
```bash
./gradlew :app:assembleDebug
```
> In the hosted environment the command fails without an Android SDK; install the SDK or provide a local.properties file when running locally.

## 4. Dependency Map
Key libraries surfaced in `app/build.gradle.kts`:

| Area | Libraries |
| --- | --- |
| Media rendering | `com.antonkarpenko:ffmpeg-kit-full-gpl:1.1.0` |
| Playback | `androidx.media3:media3-exoplayer`, `androidx.media3:media3-ui`, `androidx.media3:media3-common` |
| Capture | `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` |
| UI | Jetpack Compose (Material3, Navigation), Accompanist Permissions, Coil |
| Background work | `androidx.work:work-runtime-ktx` |
| State helpers | `androidx.lifecycle:lifecycle-runtime-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose`, `org.jetbrains.kotlinx:kotlinx-coroutines-android` |

ABI splits and packaging options are configured to handle FFmpegKit’s native binaries.

## 5. UI Architecture
* `MainActivity` creates `GifVisionViewModel`, collects `GifVisionUiState`, and renders `GifVisionApp` within `GifVisionTheme`.
* `GifVisionApp` sets up a `Scaffold` with a centered top bar, bottom navigation (Layer 1, Layer 2, Master), and a Compose Navigation host. Layer destinations pass their `layerId` argument to `LayerScreen`; the master route renders `MasterBlendScreen`.
* `LayerScreen` mirrors the manual’s layout while now wiring the upload card into Android’s document picker, surfacing imported metadata/thumbnail caches, embedding an ExoPlayer preview with transport + trim controls, and retaining the adjustments, per-stream preview, blend card, and FFmpeg log viewer. On tablets and foldables the screen automatically pivots into a responsive two-column canvas so preview/blend/log cards live side-by-side with adjustments.
* `MasterBlendScreen` exposes blend selection, opacity control, save/share placeholders, and aggregate FFmpeg logs. The screen shares the same adaptive layout strategy as `LayerScreen`, keeping controls and diagnostics visible simultaneously on larger widths.
* Both blend destinations render their diagnostics through the shared `FfmpegLogPanel` component. The panel stays pinned to each screen, auto-scrolls as new entries arrive, highlights warnings/errors, and provides copy/share affordances for escalations.
* Shared controls (`AdjustmentSlider`, `AdjustmentSwitch`, `SectionHeader`) reduce boilerplate across sections.

## 6. State Management
* `GifVisionState.kt` now exposes the canonical domain/state models used across the stack. The key classes mirror the terminology in the user manual:
  * `AdjustmentSettings` – single source for every slider/switch/text field (resolution, color palette, frame rate, text overlay, grading, experimental filters, and flips). Defaults reflect a safe starting point for renders.
  * `Stream` – wraps a `StreamSelection` slot with its `AdjustmentSettings`, media references (`sourcePreviewPath`, `generatedGifPath`), cached preview thumbnail, trim bounds, playback cursor, play/pause state, and render bookkeeping (`isGenerating`, `lastRenderTimestamp`).
  * `BlendConfig` – tracks the Stream A/B blend mode, opacity, output path, and pending job flag.
  * `Layer` – groups two `Stream` instances, the `BlendConfig`, source metadata (including cached thumbnails/duration/size), and FFmpeg logs per layer.
  * `MasterBlendConfig` – mirrors the master blend card (mode, opacity, enablement gate, output path, and logs).
  * `GifVisionUiState` – aggregates the full session, including a computed `activeLayer` pointer.
  * `SourceClip` – immutable snapshot of the imported media (URI, display name, mime type, duration, dimensions, byte size, last-modified timestamp, and thumbnail) shared across UI and pipeline layers so we never re-query the `ContentResolver` unnecessarily.
* `GifVisionViewModel` now operates on multiple `StateFlow` surfaces: the base `uiState`, derived `layerUiState(layerId)` snapshots, and validation feeds (`streamValidation`, `layerBlendValidation`, `masterBlendValidation`). The derived flows power Compose previews without manual fan-out.
* Validation is centralized in the view-model. Stream renders require a source clip, positive frame rate/duration, and a palette within 2–256 colors. Layer blends require both stream GIFs and an opacity between 0–1. The master blend checks that both layers are blended and that the final opacity remains within bounds.
* Rendering entry points include `requestStreamRender`, `requestLayerBlend`, and `requestMasterBlend`. Each method short-circuits on validation errors, toggles the `isGenerating` flags, listens to `GifProcessingCoordinator` events, persists outputs through `MediaRepository`, and appends FFmpeg-style log entries back into state.
* `shareMasterOutput` forwards the final GIF to the `ShareRepository`, enabling Compose buttons to trigger a share flow without touching Android intents.
* Log appenders keep the last 200 entries per layer/master to avoid unbounded growth and now stamp entries as `LogEntry` objects carrying severity + timestamps. UI surfaces tint warnings/errors appropriately and surface toast notifications so accessibility services announce issues immediately.
* The default constructor wires in `ScopedStorageMediaRepository`, `FfmpegKitGifProcessingCoordinator`, and a `GifProcessingNotificationAdapter.Noop` instance so background work runs inside the sandbox without extra wiring. Swap these dependencies in previews/tests to disable native encodes.


## 7. Manifest & Storage Setup
* Permissions for camera, audio, foreground services, scoped storage (Android 13+ and legacy fallbacks), notifications, and internet are declared.
* `FileProvider` is configured via `@xml/file_paths` to expose rendered GIFs securely for the share workflow.
* Theme resources renamed to `Theme.GifVision`; app label now reads “GifVision”.
* `GifVisionTheme` accepts a `highContrast` override and exposes alternate color schemes optimized for TalkBack users. The top app bar wires a high-contrast toggle (icon + switch) into the view-model so accessibility services can enable it programmatically. Use `windowSizeClass` in Compose entry points when introducing new layouts so the adaptive patterns remain consistent.

## 8. Media Pipeline Hooks
* `LayerScreen` now owns the full media import + preview flow: it launches the `OpenDocument` picker, captures persistable URI permissions, forwards selections to the view-model, renders imported metadata (resolution/duration/size/mime type), and displays cached thumbnails when present. The ExoPlayer surface is embedded via `AndroidView`, with play/pause/reset buttons, a scrubber tied to `Stream.playbackPositionMs`, and a trim `RangeSlider` that updates `Stream.trimStartMs`/`trimEndMs`.
* Stream previews use Coil to display generated GIF URIs. Completed renders and blends are persisted by `ScopedStorageMediaRepository`, so `generatedGifPath`, `blendedGifPath`, and the master output automatically resolve to sandboxed files that can be re-used across sessions.
* FFmpeg output from the new coordinator streams straight into each layer’s log list as the encode progresses. The `FfmpegLogPanel` already exposes scroll-to-bottom behavior plus copy/share shortcuts, so additional surfacing (snackbars/toasts) is only necessary for bespoke UX.
* The per-layer blend card is composed of reusable helpers (`BlendModeDropdown`, `BlendOpacitySlider`, `GenerateBlendButton`, `BlendPreviewThumbnail`). Each helper enforces the validation rules from the manual—controls stay disabled until both stream GIFs exist, sliders quantize to two decimal places across the 0.00–1.00 range, and progress feedback surfaces through a `LinearProgressIndicator` while FFmpeg runs. Reuse these primitives when expanding the blend UI so behavior stays consistent.
* `MasterBlendScreen` mirrors the same contract. Its dropdown/slider/button are locked behind `MasterBlendConfig.isEnabled`, the slider shares the 0.00–1.00 quantization, and a progress bar plus status copy render whenever `isGenerating` is true. Save/share buttons remain disabled until a master GIF path is available.

## 9. Testing Guidance
* `./gradlew :app:assembleDebug` verifies the Gradle graph and Compose compilation. Requires a configured Android SDK locally.
* Add unit tests for `GifVisionViewModel` in `app/src/test` to lock down state transitions (e.g., stream switching, opacity ranges) once logic matures.
* UI tests can target composable semantics via `androidx.compose.ui.test.junit4` once instrumentation harness is connected.

## 10. Media Service Interfaces
* `media/MediaRepository.kt` defines the persistence abstraction for source clips and rendered GIFs. Production builds now use `ScopedStorageMediaRepository`, which writes every stream render, layer blend, and master blend into `filesDir/media_store` while emitting a JSON sidecar containing identifiers, timestamps, and ownership metadata for quick rehydration. `InMemoryMediaRepository` remains available for tests.
* `media/GifProcessingCoordinator.kt` introduces the FFmpeg orchestration contract. `FfmpegKitGifProcessingCoordinator` builds palette-aware filter graphs from `AdjustmentSettings`, executes them through FFmpegKit on a dedicated IO dispatcher, forwards stdout/stderr into the log panel, and drives foreground notifications through the pluggable `GifProcessingNotificationAdapter`. The legacy `LoggingGifProcessingCoordinator` still exists for previews.
* `media/ShareRepository.kt` encapsulates share intent plumbing. `AndroidShareRepository` now owns the FileProvider URI conversion and chooser dispatch, while `LoggingShareRepository` remains available for previews/tests.
* The view-model only depends on these abstractions, keeping background execution strategies and Android intent handling in replaceable modules.
* Layer and master blend jobs now feed existing output paths back into `LayerBlendRequest.suggestedOutputPath` and `MasterBlendRequest.suggestedOutputPath`. The coordinator reuses file names inside the sandboxed render directory so repeat blends overwrite cached GIFs instead of fragmenting storage. Keep this behavior intact when adding new blend entry points so cache hits remain deterministic.

## 11. Future Work & TODOs
* Evaluate migrating the coroutine-based coordinator into WorkManager once long-running encodes with network constraints are required.
* Expand the notification adapter with concrete implementations for Android 13+ notification channels and pause/resume actions.
* Enhance metadata rehydration with thumbnail caching so the repository can repopulate previews without re-rendering.
* Add social share setup UI and connect to `GifProcessingCoordinator` once available.
* Expand theme typography and possibly add custom fonts to emphasize the brand aesthetic.

## 13. FFmpeg Pipeline Breakdown
* Stream renders use a single `-filter_complex` graph: `[0:v]setpts` → `fps` → resolution scaling → color grading → experimental effects (Spectrum Pulse, Color Cycle, Motion Trails, Sharpen, Edge Detect, flips) → optional `drawtext`, before splitting into palette generation (`palettegen`) and palette application (`paletteuse`). The spectrum, color-cycle, and motion filters rely on timeline expressions (`sin(2*PI*t)`, `H+deg*t`, `enable='gte(t,0)'`) exposed by the required FFmpegKit build.
* Blends reuse the same palette workflow but swap the pre-processing stage with FFmpeg’s `blend` filter (`all_mode` derived from `GifVisionBlendMode`, `all_opacity` wired to the UI slider). Both per-layer and master blends push their outputs through palette regeneration to avoid ghost palettes.
* `FfmpegKitGifProcessingCoordinator` estimates progress using FFmpegKit statistics, emits structured `GifProcessingEvent` updates over Kotlin flows, and persists logs so the Compose log panel mirrors native stdout/stderr in near real time.

## 12. Adjustments Panel UX Contracts
* `LayerScreen` hosts a tabbed adjustments surface (`AdjustmentTabContent`) that mirrors the manual’s groupings (Quality & Size, Text Overlay, Color & Tone, Experimental Filters). Tabs are stateful per stream via `mutableStateMapOf` keyed on `StreamSelection`, so toggling between Stream A/B restores the last viewed tab and slider positions for each stream instance.
* Each control binds directly to `AdjustmentSettings`, with slider/switch helpers enhanced to expose tooltips, supporting copy, and validation feedback (`AdjustmentValidation`). Error states tint slider tracks and surface messaging; warnings (e.g., >48 fps) render in the secondary color to differentiate from hard failures.
* `AdjustmentControls.kt` now owns the tooltip/validation plumbing. Compose’s `PlainTooltipBox` anchors an info glyph beside every labeled control; leverage the `tooltip` parameter whenever adding new adjustments so copy automatically flows through the shared scaffolding.
* Text inputs include inline helpers—font color hex codes validate against `#RRGGBB`/`#AARRGGBB` and apply the `isError` flag on the `OutlinedTextField`. When adding additional free-form inputs, mirror this pattern so validation is executed in real time before dispatching state mutations.
* The `QualitySection`, `TextOverlaySection`, `ColorSection`, and `ExperimentalSection` no longer emit static headers. They assume the tab title provides context, so new controls should add spacing manually (`Spacer(height = 12.dp)`) before the first widget to maintain visual rhythm within each tab.
* `AdjustmentSlider` accepts an `enabled` flag and stays responsible for formatting value text. Use the new parameter when gating controls behind render prerequisites (e.g., blend sliders) so Compose keeps a single appearance contract.

## 14. Blend Workflow Reference
* `MainActivity` wires `GifVisionApp` with explicit callbacks for `requestLayerBlend` and `requestMasterBlend`. These flows flip the `isGenerating` flags, surface progress logs, and refresh enablement on completion.
* `GifVisionApp` keeps navigation aware of blend readiness. Layer routes forward `onGenerateBlend` into `LayerScreen`, while the master route pipes `onGenerateMasterBlend` to `MasterBlendScreen`.
* `LayerScreen`’s blend card disables user interaction until both stream GIFs exist, shows readiness messaging, and exposes preview/progress affordances. When extending the card, respect the existing enablement guard so we never dispatch a blend against missing media.
* `MasterBlendScreen` mirrors the layer experience and unlocks only after both layers report blended GIFs. Any master-level extension should check `MasterBlendConfig.isEnabled` before issuing renders.
* `GifVisionViewModel.requestLayerBlend` and `.requestMasterBlend` now pass cached output paths into their FFmpeg requests to keep filesystem churn low. The coordinator still executes asynchronously on its dedicated scope and relays completion back into state before enabling the next blend step.
* Oversized clips trigger normalization inside `importSourceClip`: duration/frame-rate defaults are clamped, resolution is stepped down to 60%, and the view model logs high-severity warnings whenever the imported asset could exceed social upload limits.
* All FFmpeg jobs emit explicit `Cancelled` events. The view-model clears `isGenerating` flags, posts warning toasts, and keeps the UI in sync when background execution is revoked or the activity leaves the foreground.

For any questions, start at `MainActivity.kt` to trace the data flow, then inspect the state layer for mutation patterns.

## 15. Save & Share Enhancements
* Master preview now uses Coil's GIF decoder pipeline (ImageDecoder/GifDecoder) and surfaces loading/error states directly in `MasterBlendScreen`.
* The CTA row exposes "Generate/Regenerate", "Save", and "Share" actions. Save writes to public Downloads via `MediaStore` on the IO dispatcher, while Share defers to `AndroidShareRepository` for FileProvider conversion + chooser dispatch and disables the button while preparing.
* `GifVisionViewModel` manages a `ShareSetupState` that tracks caption copy, parsed hashtags, loop metadata, and live previews for Instagram/TikTok/X. Mutation helpers recompute previews and sanitise tags (duplicate removal, alphanumeric enforcement).
* `ShareSetupCard` renders the social setup UI with caption + hashtag inputs, loop metadata chips, and destination-specific preview cards that highlight character and hashtag limits. The previews update instantly as the view-model state mutates.
* Toast feedback is centralised through `GifVisionViewModel.UiMessage` and consumed in `MainActivity`, ensuring save/share success/failure notices flow through a single Compose-side collector.
