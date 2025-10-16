# GifVision Engineering Guide

## Table of Contents
- [1. Introduction](#1-introduction)
- [2. Getting Started](#2-getting-started)
  - [2.1. Platform Requirements](#21-platform-requirements)
  - [2.2. Repository Layout](#22-repository-layout)
  - [2.3. Build & Verification Commands](#23-build--verification-commands)
- [3. Application Architecture](#3-application-architecture)
  - [3.1. High-Level Flow](#31-high-level-flow)
  - [3.2. Entry Point](#32-entry-point)
  - [3.3. State Container & Business Logic](#33-state-container--business-logic)
  - [3.4. Navigation & Routing](#34-navigation--routing)
- [4. UI Surface Reference](#4-ui-surface-reference)
  - [4.1. Theme & Accessibility](#41-theme--accessibility)
  - [4.2. Scaffold Shell (`GifVisionApp`)](#42-scaffold-shell-gifvisionapp)
  - [4.3. Layer Screen (`LayerScreen`)](#43-layer-screen-layerscreen)
  - [4.4. Master Blend Screen (`MasterBlendScreen`)](#44-master-blend-screen-masterblendscreen)
  - [4.5. Reusable UI Components](#45-reusable-ui-components)
- [5. Media & Storage Pipeline](#5-media--storage-pipeline)
  - [5.1. Media Repository Abstractions](#51-media-repository-abstractions)
  - [5.2. FFmpeg Coordination](#52-ffmpeg-coordination)
  - [5.3. Share Workflow](#53-share-workflow)
- [6. Domain Model & Validation](#6-domain-model--validation)
  - [6.1. State Models](#61-state-models)
  - [6.2. Validation Strategy](#62-validation-strategy)
  - [6.3. Logging & Diagnostics](#63-logging--diagnostics)
- [7. Resource & Manifest Configuration](#7-resource--manifest-configuration)
- [8. Development Workflows](#8-development-workflows)
  - [8.1. Testing](#81-testing)
  - [8.2. Previewing UI](#82-previewing-ui)
  - [8.3. Adding New Features Safely](#83-adding-new-features-safely)
- [9. File-by-File Reference Appendix](#9-file-by-file-reference-appendix)
- [10. Future Enhancements & Open Questions](#10-future-enhancements--open-questions)

---

## 1. Introduction
GifVision is a Jetpack Compose Android application focused on orchestrating complex GIF creation workflows. The current codebase provides a production-ready skeleton that wires together:

* An adaptive Compose UI with a navigation shell and high-contrast accessibility toggle.
* A comprehensive state container that mirrors the concepts in the user manual (streams, layers, master blend, share prep).
* A media pipeline capable of simulating FFmpeg jobs in preview builds and executing native FFmpegKit commands in production.
* Repository abstractions for scoped storage persistence and Android share intents.

This document serves as a field manual for engineers extending the project. It aligns with the repository as checked in and is intended to be exhaustive—treat it as the authoritative reference for architecture, data flow, and developer workflows.

## 2. Getting Started

### 2.1. Platform Requirements
The project is built with the Android Gradle Plugin and Jetpack Compose. Ensure the following prerequisites are installed locally:

| Dependency | Required Version | Notes |
| --- | --- | --- |
| Android Studio | Ladybug or newer | Compose tooling requires a modern IDE. |
| Android SDK | API 36 (Android 15) | `compileSdk`/`targetSdk` are set to 36 in [`app/build.gradle.kts`](app/build.gradle.kts). |
| Java | JDK 17 | FFmpegKit and Media3 ship Java 17 bytecode. Gradle toolchains are aligned. |
| Gradle | Wrapper-provided (8.x) | Use `./gradlew` to avoid mismatched versions. |

An Android SDK must be configured through `local.properties` (`sdk.dir=/path/to/sdk`) or environment variables (`ANDROID_HOME`, `ANDROID_SDK_ROOT`) before running assemble tasks.

### 2.2. Repository Layout
The project is a single-module Android application. Key paths:

```
.
├── app/
│   ├── build.gradle.kts          # Module build configuration & dependencies
│   ├── proguard-rules.pro        # Release build ProGuard configuration
│   └── src/main/
│       ├── AndroidManifest.xml   # Permissions & component declarations
│       ├── java/com/gifvision/app/
│       │   ├── MainActivity.kt                   # Activity entry point
│       │   ├── navigation/GifVisionDestination.kt # Route definitions
│       │   ├── media/                            # Media + share contracts
│       │   │   ├── GifProcessingCoordinator.kt
│       │   │   ├── MediaRepository.kt
│       │   │   └── ShareRepository.kt
│       │   └── ui/
│       │       ├── GifVisionApp.kt               # Scaffold & NavHost
│       │       ├── layer/                         # Layer feature surface
│       │       ├── master/                        # Master blend surface
│       │       ├── components/                    # Reusable Compose widgets
│       │       │   ├── AdjustmentControls.kt
│       │       │   ├── BlendControlsCard.kt
│       │       │   ├── components/preview/        # Shared preview scaffolds
│       │       │   │   ├── GifPreviewCard.kt
│       │       │   │   └── PreviewPlacement.kt
│       │       │   └── FfmpegLogPanel.kt
│       │       ├── layout/                        # Window metrics + layout helpers
│       │       │   ├── LayoutMetrics.kt
│       │       │   └── UiLayoutConfig.kt
│       │       ├── resources/                     # Centralized UI copy/constants
│       │       │   ├── LayerCopy.kt
│       │       │   ├── LogCopy.kt
│       │       │   └── PanelCopy.kt
│       │       ├── state/                         # Immutable state models + VM helpers
│       │       │   ├── GifVisionState.kt
│       │       │   ├── GifVisionViewModel.kt
│       │       │   ├── coordinators/              # Clip import/render/share delegates
│       │       │   │   ├── ClipImporter.kt
│       │       │   │   ├── RenderScheduler.kt
│       │       │   │   └── ShareCoordinator.kt
│       │       │   ├── messages/                  # Toast/snackbar plumbing
│       │       │   │   └── MessageCenter.kt
│       │       │   └── validation/                # Validation models + rules
│       │       │       ├── ValidationModels.kt
│       │       │       └── ValidationRules.kt
│       │       └── theme/                        # Material 3 theming primitives
│       │           ├── Color.kt
│       │           ├── Theme.kt
│       │           └── Type.kt
│       └── res/                 # Material colors, strings, icons, FileProvider XML
├── ENGINEGUIDE.md               # This engineering manual
├── docs/phase4/                 # Documentation & test planning artifacts
├── USERMANUAL.txt               # Customer-facing workflow description
├── build.gradle.kts             # Root Gradle setup
├── settings.gradle.kts          # Gradle module declaration
└── gradle/                      # Wrapper files
```

### 2.3. Build & Verification Commands
The standard Gradle lifecycle commands apply:

```bash
./gradlew :app:assembleDebug       # Compiles the app and packages a debug APK
./gradlew :app:lint                # Static analysis (requires configured SDK)
./gradlew :app:testDebugUnitTest   # Runs JVM unit tests when present
```

> **Hosted CI note:** In containerized environments without the Android SDK, `assembleDebug` and lint tasks fail. Install the SDK or attach a preconfigured `local.properties` when running locally.

## 3. Application Architecture

### 3.1. High-Level Flow
1. **MainActivity** boots, verifies FFmpeg drawtext availability (`FfmpegKitGifProcessingCoordinator.verifyDrawtextSupport()`), and sets the Compose content tree.
2. **GifVisionViewModel** exposes `StateFlow` state describing layers, streams, blend configuration, share prep, and accessibility toggles.
3. **GifVisionApp** renders a Material 3 scaffold with a top bar, bottom navigation, and `NavHost` for the two layer routes plus the master blend route. Layout responsiveness is driven by `UiLayoutConfig`/`LayoutMetrics`, keeping width breakpoints and column counts centralized.
4. Screen composables (`LayerScreen`, `MasterBlendScreen`) render responsive layouts, dispatch mutations back to the view-model, and consume validation/logging signals.
5. Media operations and share requests pass through repository abstractions, enabling replacement during previews/tests.

### 3.2. Entry Point
[`MainActivity.kt`](app/src/main/java/com/gifvision/app/MainActivity.kt) is a `ComponentActivity` that:

* Instantiates `GifVisionViewModel` via the provided factory.
* Runs `verifyDrawtextSupport()` once on startup for early diagnostics.
* Enables edge-to-edge rendering and captures the `WindowSizeClass` for responsive UI.
* Collects `uiState` and `uiMessages` from the view-model using `collectAsStateWithLifecycle()` and `LaunchedEffect`, respectively. Toasts surface all `UiMessage`s.
* Passes hoisted callbacks (import clip, adjustments, blend requests, share requests, toggles) down into `GifVisionApp`.

### 3.3. State Container & Business Logic
[`GifVisionViewModel`](app/src/main/java/com/gifvision/app/ui/state/GifVisionViewModel.kt) centralizes domain logic:

* Holds a `MutableStateFlow<GifVisionUiState>` representing the entire session while surfacing derived screen state and validation results through lightweight adapters.
* Delegates complex workflows to feature-specific coordinators:
  * `ClipImporter` performs metadata extraction, repository registration, default adjustment normalization, and thumbnail capture when a user selects a new clip.
  * `RenderScheduler` translates UI intent into FFmpeg `Flow`s, mirrors progress via the `MessageCenter`, persists outputs with `MediaRepository`, and tracks in-flight jobs for cancellation.
  * `ShareCoordinator` assembles export/share requests, normalizes captions/hashtags, and fans out toast notifications through the shared message surface.
* Surfaces transient toasts/snackbars via `MessageCenter`, which deduplicates bursts of identical messages and exposes them as a `SharedFlow<UiMessage>` for the activity shell.
* Applies validation centrally using the pure helpers in `ui/state/validation/ValidationRules.kt`, keeping UI enablement logic synchronized across layer, stream, and master surfaces.
* Maintains adjustments, trims, playback, blend modes, opacities, and share metadata with focused helper methods while recording all events in the per-layer FFmpeg log buffers.
* Routes persistence tasks through `MediaRepository` (stream/layer/master saves, downloads export) and ensures job completion metadata updates the state snapshots consistently.
* Keeps accessibility toggles and share previews synchronized as users edit settings, leveraging the shared resources/preview scaffolds introduced during Phases 1–3.

The view-model depends on:

* `ScopedStorageMediaRepository` for persistence with JSON sidecar metadata.
* Coordinator helpers under `ui/state/coordinators/` (`ClipImporter`, `RenderScheduler`, `ShareCoordinator`) and `MessageCenter` in `ui/state/messages/`.
* `FfmpegKitGifProcessingCoordinator` for real FFmpeg execution (with `LoggingGifProcessingCoordinator` available for previews/tests).
* `AndroidShareRepository` for launching share intents.

### 3.4. Navigation & Routing
Routing lives in [`GifVisionDestination.kt`](app/src/main/java/com/gifvision/app/navigation/GifVisionDestination.kt):

* `Layer` destination defines `route = "layer/{layerId}"` with integer argument `layerId` and a helper `createRoute(layerId: Int)`.
* `MasterBlend` destination exposes a simple `"master"` route.
* `GifVisionApp` builds bottom navigation items from the current `uiState.layers`. The active layer index triggers navigation updates to keep the back stack aligned with state changes.

## 4. UI Surface Reference

### 4.1. Theme & Accessibility
`ui/theme` hosts Material 3 theming primitives:

* [`Theme.kt`](app/src/main/java/com/gifvision/app/ui/theme/Theme.kt) defines `GifVisionTheme(highContrast: Boolean, content: @Composable () -> Unit)` which swaps between default and high-contrast color palettes.
* [`Color.kt`](app/src/main/java/com/gifvision/app/ui/theme/Color.kt) provides baseline palettes plus boosted-contrast variants.
* [`Type.kt`](app/src/main/java/com/gifvision/app/ui/theme/Type.kt) holds typography overrides.

The top bar exposes both an icon button and a switch for toggling high contrast, with accessibility semantics describing the current state. `GifVisionViewModel` persists the preference in `uiState.isHighContrastEnabled`.

### 4.2. Scaffold Shell (`GifVisionApp`)
[`GifVisionApp.kt`](app/src/main/java/com/gifvision/app/ui/GifVisionApp.kt) sets up the application shell:

* **Top App Bar:** `CenterAlignedTopAppBar` labeled "GifVision" with high-contrast toggle controls.
* **Bottom Navigation:** Dynamically generated `NavigationBarItem`s for Layer 1, Layer 2, and Master Blend. Selection logic derives from the current `NavBackStackEntry`.
* **Navigation Host:** `NavHost` with:
  * `layer/{layerId}` → `LayerScreen`
  * `master` → `MasterBlendScreen`
* **Window Size Responsiveness:** Derives `LayoutMetrics` from `UiLayoutConfig`, giving each screen consistent breakpoints, column counts, and spacing regardless of device form factor.
* **Lifecycle Hooks:** `LaunchedEffect(uiState.activeLayerIndex)` keeps navigation synchronized when the active layer changes via the state toggle.

### 4.3. Layer Screen (`LayerScreen`)
[`LayerScreen.kt`](app/src/main/java/com/gifvision/app/ui/LayerScreen.kt) manages a single layer composed of two streams:

* **Upload Card:** Launches `ActivityResultContracts.OpenDocument()` and hands the resulting URI to `ClipImporter`, which normalizes adjustments, registers metadata, and emits any onboarding warnings.
* **Video Preview Card:** Wraps the ExoPlayer `AndroidView` inside `GifPreviewCard`, letting the shared scaffold manage titles, progress indicators, and action rows while layer-specific transport controls slot in beneath the preview.
* **Adjustments Card:** Uses tabbed content to surface sliders/switches for Quality & Size, Text Overlay, Color & Tone, and Experimental filters. Controls pull shared copy from `LayerCopy` and reuse `AdjustmentSlider`/`AdjustmentSwitch` for consistent semantics.
* **Stream Preview Cards:** Leverage `GifPreviewCard` to present generated GIFs, combining progress state, regeneration buttons, and download actions across both streams via a single reusable scaffold.
* **Blend Card:** Builds on `BlendControlsCard`/`BlendControlsContent`, consolidating dropdown, opacity slider, progress messaging, and `GifPreviewCard` placement options while validation decisions come from `ValidationRules`.
* **FFmpeg Log Panel:** `FfmpegLogPanel` now pairs with `rememberLogPanelState`, centralizing auto-scroll, share, and clipboard behaviour so both the layer and master screens present identical controls. `LogPanelState` delegates all copy/share/toast effects through `LogPanelSideEffects`, making Android plumbing swappable for test doubles.
* **Responsive Layout:** `LayoutMetrics` dictate column groupings and gutter spacing, ensuring the layer surface aligns with master layouts on tablets, desktops, and compact handsets alike.

### 4.4. Master Blend Screen (`MasterBlendScreen`)
[`MasterBlendScreen.kt`](app/src/main/java/com/gifvision/app/ui/MasterBlendScreen.kt) orchestrates the final composition:

* Highlights aggregated readiness for the master blend (requires both layer blends) using the shared validation helpers.
* Provides blend mode dropdown, opacity slider, and generate button through `BlendControlsCard`, giving parity with the layer surface.
* Presents the master preview via `GifPreviewCard`, sharing progress chrome and action rows (save/share) across all preview surfaces.
* Surfaces the share setup card with centralized copy/hashtag helpers and platform previews (Instagram, TikTok, X) powered by the shared preview scaffold.
* Maintains a dedicated FFmpeg log panel backed by `rememberLogPanelState`, matching the interaction model established on the layer screen.

### 4.5. Reusable UI Components
The `ui/components` package offers shared building blocks:

* [`AdjustmentControls.kt`](app/src/main/java/com/gifvision/app/ui/components/AdjustmentControls.kt): `AdjustmentSlider`, `AdjustmentSwitch`, and `AdjustmentValidation` provide consistent styling, tooltip support, and validation messaging across adjustment tabs.
* [`components/preview/GifPreviewCard.kt`](app/src/main/java/com/gifvision/app/ui/components/preview/GifPreviewCard.kt): Shared scaffold for stream, blend, and share previews. Accepts title, progress, primary/secondary actions, and `PreviewPlacement` to position media relative to controls.
* [`BlendControlsCard.kt`](app/src/main/java/com/gifvision/app/ui/components/BlendControlsCard.kt) and `BlendControlsContent`: Consolidate blend dropdown, opacity slider, progress text, and action wiring so layer/master screens reuse identical affordances.
* [`FfmpegLogPanel.kt`](app/src/main/java/com/gifvision/app/ui/components/FfmpegLogPanel.kt) with `rememberLogPanelState`: Renders scrollable log entries with severity badges, copy/share actions, auto-scroll toggles, and optional title/empty state messaging while routing copy/share/toast side effects through `LogPanelSideEffects`.

Reuse these components when adding new controls to minimize styling drift and ensure accessibility support remains consistent.

## 5. Media & Storage Pipeline

### 5.1. Media Repository Abstractions
[`MediaRepository.kt`](app/src/main/java/com/gifvision/app/media/MediaRepository.kt) defines the persistence layer:

* **Interface:** Declares methods to register source clips, store stream outputs, store layer blends, store master blends, fetch source clips, and export files to the public downloads directory.
* **Data Classes:**
  * `MediaAsset` describes stored artifacts with IDs, optional layer/stream ownership, canonical paths, and timestamps.
  * `MediaSource` captures registered clip metadata.
* **Implementations:**
  * `InMemoryMediaRepository` – test/preview double storing assets in memory.
  * `ScopedStorageMediaRepository` – production implementation writing files beneath `filesDir/media_store` with subfolders for sources, streams, and blends. Persists JSON sidecars for quick rehydration and synchronizes access with a `Mutex`. Provides `exportToDownloads` handling API level differences (`MediaStore` for API ≥ 29, legacy paths otherwise).

The view-model defaults to `ScopedStorageMediaRepository` but can swap to `InMemoryMediaRepository` via dependency injection in previews or tests.

### 5.2. FFmpeg Coordination
[`GifProcessingCoordinator.kt`](app/src/main/java/com/gifvision/app/media/GifProcessingCoordinator.kt) wraps FFmpeg execution:

* **Interface Methods:** `renderStream`, `blendLayer`, `mergeMaster` returning `Flow<GifProcessingEvent>` for reactive progress updates.
* **Job Requests:**
  * `StreamProcessingRequest` bundles layer, stream, source path, adjustments, trims, and optional output path hints.
  * `LayerBlendRequest` captures stream GIF paths, blend mode, opacity, and output suggestions.
  * `MasterBlendRequest` merges layer outputs with blend parameters.
* **Job Registry:** `RenderJobRegistry` generates deterministic job IDs for stream renders, layer blends, and master blends so `RenderScheduler` and the coordinator share a single naming contract.
* **Events:** `GifProcessingEvent` sealed class with `Started`, `Progress`, `Completed`, `Failed`, and `Cancelled` states.
* **Implementations:**
  * `LoggingGifProcessingCoordinator` – deterministic simulator emitting progress/log strings without invoking FFmpeg.
  * `FfmpegKitGifProcessingCoordinator` – production executor interfacing with FFmpegKit, translating `AdjustmentSettings` into filter graphs, monitoring `StatisticsCallback`, persisting outputs through `MediaRepository`, and piping logs back through the notification adapter. It provides `verifyDrawtextSupport()` used at startup to confirm `drawtext` availability.
* **Notification Adapter:** `GifProcessingNotificationAdapter` allows integrating Android foreground service notifications. `GifProcessingNotificationAdapter.Noop` is used by default but can be replaced with a concrete adapter when background execution policies require it.
* **UI Bridge:** `RenderScheduler` lives in `ui/state/coordinators/` and coordinates the flows emitted here with UI callbacks, `MediaRepository`, and the shared message surface.

### 5.3. Share Workflow
[`ShareRepository.kt`](app/src/main/java/com/gifvision/app/media/ShareRepository.kt) abstracts Android share intent plumbing:

* `ShareRequest` includes output path, display name, caption, hashtags, and loop metadata.
* `ShareResult` surfaces success or failure.
* `AndroidShareRepository` transforms filesystem paths into `content://` URIs via `FileProvider`, composes share text from caption/hashtags/loop metadata, grants URI permissions to all resolved targets, and launches a chooser intent.
* `LoggingShareRepository` is available for tests and previews where actual intents are undesirable.

`GifVisionViewModel.shareMasterOutput()` orchestrates the share flow, preventing duplicate launches (`isPreparingShare`) and relaying completion status through `UiMessage`s.

## 6. Domain Model & Validation

### 6.1. State Models
[`GifVisionState.kt`](app/src/main/java/com/gifvision/app/ui/state/GifVisionState.kt) defines immutable data classes for the entire domain:

* Enumerations for `StreamSelection`, `GifVisionBlendMode` (FFmpeg keywords), `GifLoopMetadata`, `LogSeverity`, and `SharePlatform`.
* Value objects:
  * `LogEntry` with severity and timestamps for diagnostics.
  * `PlatformPreview` capturing per-destination caption counts, hashtag stats, and loop guidance.
  * `ShareSetupState` storing caption, hashtags, loop metadata, previews, and `isPreparingShare` flag.
  * `AdjustmentSettings` enumerating every slider/switch bound in the UI (resolution, max colors, frame rate, text overlay, color balance, experimental effects, flips, etc.).
  * `Stream` describing stream-specific adjustments, previews, playback/trimming state, and render status.
  * `BlendConfig` representing per-layer blend mode, opacity, output path, and `isGenerating` flag.
  * `Layer` combining source clip metadata, two streams, blend state, active stream selection, and FFmpeg logs.
  * `SourceClip` storing imported media metadata and thumbnail.
  * `MasterBlendConfig` containing master blend parameters, enablement gates, logs, and share setup.
  * `GifVisionUiState` bundling layers, master blend, active layer index, and high-contrast status.

### 6.2. Validation Strategy
Validation is centralized in `ui/state/validation/ValidationRules.kt` and consumed by the view-model:

* Stream validation ensures a source clip is present, frame rate stays within supported bounds, clip duration is positive, and palette size remains 2–256 colors.
* Layer blend validation requires both stream GIFs, constrains opacity to 0.0–1.0, and guards against incompatible negate/blend-mode combinations via `detectUnsupportedLayerBlend`.
* Master blend validation checks layer blends before enabling the master generate button and prevents conflicting master/layer blend mode combinations through `detectUnsupportedMasterBlend`.
* Validation results are cached per-layer/per-stream to avoid recomputation. The UI inspects validation states to enable/disable controls and surface messages near adjustments via `AdjustmentValidation`.

### 6.3. Logging & Diagnostics
Logging is first-class:

* The view-model emits `UiMessage`s for toast notifications (warnings, errors, successes).
* `appendLog(layerId, message, severity)` attaches `LogEntry`s to the appropriate layer or master list. Logs are truncated to avoid unbounded growth.
* `FfmpegLogPanel` displays log entries with severity colors and copy support, enabling engineers to extract FFmpeg command output easily.
* Startup diagnostics check FFmpeg `drawtext` support and log an error if unavailable.

## 7. Resource & Manifest Configuration

* [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) declares required permissions for internet, camera, audio, scoped storage, foreground services, notifications, and file provider integration. It also configures optional camera hardware for ChromeOS compatibility.
* [`res/xml/file_paths.xml`](app/src/main/res/xml/file_paths.xml) defines the `FileProvider` root used by the share workflow.
* `values/strings.xml`, `values/colors.xml`, and `values/themes.xml` host app name, brand colors, and the Material 3 theme bridge.
* Launcher icons live in `mipmap-*/` directories with vector/drawable definitions.

## 8. Development Workflows

### 8.1. Testing
Unit coverage now exercises the extracted helpers introduced in earlier phases:

* `ValidationRulesTest` verifies stream, layer, and master validation logic, keeping `ValidationRules.kt` trustworthy as the single source of enablement truth.
* `RenderSchedulerTest` drives the coordinator with fakes to assert job ID wiring, persistence interactions, and log lifecycle events without launching FFmpeg.
* `ShareCoordinatorTest` and `MessageCenterTest` cover share/export flows plus toast deduplication, demonstrating how to exercise coroutine-driven helpers deterministically.
* `ClipImporterTest` focuses on the reset pathway to confirm that stream/blend state clearing stays consistent even without Android content resolver access.
* `LogPanelStateTest` validates log buffer formatting helpers (`toDisplayString`, `toLogTimestamp`, and `refresh`) and the copy/share flows via injectable `LogPanelSideEffects` doubles, enabling JVM verification of toast messaging and share error handling.
* `BlendControlsAvailabilityTest` verifies layer/master blend enablement gating and associated action toggles using the extracted availability helpers.

Run `./gradlew test --console=plain` to execute the JVM test suite. Instrumented Compose tests still live under `app/src/androidTest` and require a configured Android SDK for execution.

> **Environment note:** Containerized environments without the Android SDK will fail Gradle configure steps. Provide `local.properties` with `sdk.dir` or set `ANDROID_SDK_ROOT` before running assemble or instrumentation commands.

### 8.2. Previewing UI
Compose previews are not currently defined, but engineers can:

* Create preview functions referencing `GifVisionApp` with fake `GifVisionUiState` instances.
* Swap `GifVisionViewModel` dependencies for `LoggingGifProcessingCoordinator` and `InMemoryMediaRepository` when wiring previews to avoid native FFmpeg execution.

### 8.3. Adding New Features Safely
* Respect existing abstractions—route UI mutations through `GifVisionViewModel` so state remains single-sourced.
* When adding adjustments, extend `AdjustmentSettings` and augment FFmpeg graph construction in `FfmpegKitGifProcessingCoordinator`.
* For new share destinations, update `SharePlatform` enum and extend preview logic in `buildPlatformPreview`.
* Preserve validation gating to maintain UX guarantees (disable buttons until prerequisites are satisfied).
* Follow `MediaRepository` contracts when introducing alternative persistence strategies (e.g., cloud sync).

## 9. File-by-File Reference Appendix
| File | Description |
| --- | --- |
| `app/src/main/java/com/gifvision/app/MainActivity.kt` | Activity entry point: sets Compose content, gathers window size, collects state/toasts, and forwards callbacks. |
| `app/src/main/java/com/gifvision/app/navigation/GifVisionDestination.kt` | Route definitions for layer/master destinations with helper builders. |
| `app/src/main/java/com/gifvision/app/ui/GifVisionApp.kt` | Scaffold, top app bar, bottom navigation, layout metrics, and Compose `NavHost`. |
| `app/src/main/java/com/gifvision/app/ui/layer/LayerScreen.kt` | Layer feature assembly: import, playback, adjustments, previews, blend controls, and logs. |
| `app/src/main/java/com/gifvision/app/ui/master/MasterBlendScreen.kt` | Final blend workflow including shared preview scaffold and share setup diagnostics. |
| `app/src/main/java/com/gifvision/app/ui/components/AdjustmentControls.kt` | Shared adjustment slider/switch components with tooltip & validation support. |
| `app/src/main/java/com/gifvision/app/ui/components/BlendControlsCard.kt` | Blend dropdown/slider/action wrapper reused by layer and master surfaces. |
| `app/src/main/java/com/gifvision/app/ui/components/preview/GifPreviewCard.kt` | Generic preview scaffold consumed by stream, blend, and share preview cards. |
| `app/src/main/java/com/gifvision/app/ui/components/FfmpegLogPanel.kt` | Scrollable FFmpeg log surface paired with `rememberLogPanelState`. |
| `app/src/main/java/com/gifvision/app/ui/layout/UiLayoutConfig.kt` | Computes window-size-aware layout metrics for responsive scaffolds. |
| `app/src/main/java/com/gifvision/app/ui/layout/LayoutMetrics.kt` | Data class describing column counts, gutters, and padding derived from `UiLayoutConfig`. |
| `app/src/main/java/com/gifvision/app/ui/resources/LayerCopy.kt` | Centralized strings for layer upload, adjustments, and preview messaging. |
| `app/src/main/java/com/gifvision/app/ui/resources/PanelCopy.kt` | Shared titles/empty states for log and preview panels. |
| `app/src/main/java/com/gifvision/app/ui/state/coordinators/ClipImporter.kt` | Coordinator handling clip metadata extraction, repository registration, and default normalization. |
| `app/src/main/java/com/gifvision/app/ui/state/coordinators/RenderScheduler.kt` | FFmpeg job orchestration, progress fan-out, and persistence hand-offs. |
| `app/src/main/java/com/gifvision/app/ui/state/coordinators/ShareCoordinator.kt` | Share/save delegation, caption normalization, and toast routing. |
| `app/src/main/java/com/gifvision/app/ui/state/messages/MessageCenter.kt` | SharedFlow-backed toast/snackbar helper with deduplication window. |
| `app/src/main/java/com/gifvision/app/ui/state/validation/ValidationRules.kt` | Pure validation utilities for streams, layer blends, and master blend readiness. |
| `app/src/main/java/com/gifvision/app/ui/state/GifVisionState.kt` | Immutable domain/state models for streams, layers, master blend, share setup, and logs. |
| `app/src/main/java/com/gifvision/app/ui/state/GifVisionViewModel.kt` | Business logic delegating to coordinators, validation helpers, and repositories. |
| `app/src/main/java/com/gifvision/app/ui/theme/Color.kt` | Color palettes supporting default and high-contrast themes. |
| `app/src/main/java/com/gifvision/app/ui/theme/Theme.kt` | Material 3 theme wrapper with high-contrast support. |
| `app/src/main/java/com/gifvision/app/ui/theme/Type.kt` | Typography definitions for Material 3 surfaces. |
| `app/src/main/java/com/gifvision/app/media/GifProcessingCoordinator.kt` | FFmpeg orchestration contracts, request/event models, and simulator/production implementations. |
| `app/src/main/java/com/gifvision/app/media/MediaRepository.kt` | Persistence abstractions with in-memory and scoped-storage implementations plus export helpers. |
| `app/src/main/java/com/gifvision/app/media/ShareRepository.kt` | Share intent abstraction and Android implementation using `FileProvider`. |
| `app/src/main/AndroidManifest.xml` | Permission declarations, activity registration, and FileProvider setup. |
| `app/src/main/res/xml/file_paths.xml` | FileProvider path configuration. |
| `app/build.gradle.kts` | Module-level Gradle configuration, Compose enablement, dependency declarations, ABI splits, and packaging rules. |

## 10. Future Enhancements & Open Questions
1. **Notification Adapter:** Provide a concrete implementation for Android 13+ notification channels to surface FFmpeg progress outside the app.
2. **WorkManager Integration:** Investigate migrating long-running FFmpeg jobs into foreground `WorkRequest`s for improved resiliency.
3. **Metadata Rehydration:** Expand `ScopedStorageMediaRepository` to eagerly load JSON sidecars on startup so sessions resume with cached outputs/thumbnails.
4. **Testing Infrastructure:** Introduce unit tests for `GifVisionViewModel` and integration tests covering navigation and blend flows.
5. **Compose Previews:** Author preview functions for key screens to accelerate UI development and regression testing.
6. **Localization:** Externalize user-facing strings for translation readiness.
7. **Analytics Hooks:** If telemetry is required, instrument state transitions and share actions through a dedicated analytics interface to avoid polluting the view-model.

---

This guide should be updated whenever new features land or architecture decisions evolve. Treat it as a living document that mirrors the actual codebase.
