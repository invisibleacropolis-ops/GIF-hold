# Phase 4 Test Plan Outline

## Goals
- Provide JVM unit coverage for validation utilities extracted in Phase 2 (stream, layer, master).
- Exercise coordinator helpers (`ClipImporter`, `RenderScheduler`, `ShareCoordinator`, `MessageCenter`) with deterministic fakes to validate side effects and messaging.
- Establish regression tests for shared UI adapters where practical (e.g., `LogPanelState` logic) using Compose rule-free unit tests.

## Target Test Suites
1. **`ui/state/validation` package**
   - `StreamValidationTest` – verifies trim bounds, bitrate/size constraints, and ensures mixed validation states aggregate correctly.
   - `LayerValidationTest` – asserts blend enablement flags respond to stream readiness permutations.
   - `MasterValidationTest` – confirms master blend readiness gating and share preconditions.
2. **Coordinator Helpers**
   - `ClipImporterTest` – simulate metadata retrieval success/failure, URI persistence, and log emission routing.
   - `RenderSchedulerTest` – validate job dispatch semantics, render job registry usage, and progress propagation to callbacks.
   - `ShareCoordinatorTest` – cover share request assembly, caption trimming, and hashtag normalization.
   - `MessageCenterTest` – ensure toast deduplication and severity mapping operate as expected.
3. **Shared Component Logic**
   - `LogPanelStateTest` – confirm buffer size, auto-scroll toggles, and copy/share callback invocation.
   - `BlendControlsAvailabilityTest` – assert enablement states under different stream/master readiness inputs.

## Test Infrastructure Notes
- Use Mockito or manual fake implementations to stub repository dependencies (media, share, notification adapter).
- Provide sample `GifVisionState` builders for easier scenario composition.
- Document Android SDK dependency and configure Gradle to skip instrumented tooling when SDK is unavailable (README note).

## Reporting & Maintenance
- Capture expected Gradle command (`./gradlew testDebugUnitTest`) and known environment caveats.
- Outline CI follow-ups: integrate tests into pipeline after SDK provisioning, monitor for flaky behavior.
- Add guidance for contributors on expanding coverage as new shared utilities appear.

## Progress Log (2025-10-26)
- ✅ Added JVM coverage for validation helpers via `ValidationRulesTest`, exercising stream/layer/master pathways.
- ✅ Implemented coordinator tests: `RenderSchedulerTest`, `ShareCoordinatorTest`, `MessageCenterTest`, and new `ClipImporterTest` (reset behaviour) with deterministic fakes.
- ✅ Authored `LogPanelStateTest` to validate log buffer formatting helpers outside of Compose runtime.
- ⏳ Pending: isolated tests for clipboard/share side-effects in `LogPanelState` (requires instrumentation or Robolectric harness).
- ⏳ Pending: enablement matrix checks for blend controls (`BlendControlsAvailabilityTest`) once UI wiring is further abstracted for unit coverage.

## Progress Log (2025-10-27)
- ✅ Extracted layer/master blend enablement helpers and covered them with `BlendControlsAvailabilityTest` (controls + action enablement regressions).
- ⏳ Pending: Compose-driven clipboard/share side-effect coverage for `LogPanelState` (blocked on Android SDK/instrumentation).

## Progress Log (2025-10-28)
- ✅ Extended `LogPanelStateTest` with injectable side-effect fakes to exercise clipboard copy, toast messaging, and share failure handling without Android instrumentation.
- ⏳ Pending: Compose-driven clipboard/share side-effect coverage for `LogPanelState` (still requires Android SDK or Robolectric for UI-layer verification).

## Progress Log (2025-10-29)
- ✅ Normalized `ShareCoordinator` display name sanitization and updated the JVM test to assert the new contract alongside successful export messaging.
- ✅ Provisioned the Android SDK locally and executed `./gradlew test --console=plain` to verify the full Phase 4 suite passes.
- ✅ Documentation refreshed (`ENGINEGUIDE.md`) to capture the sanitized share workflow and accompanying tests.
- ✅ Phase 4 scope complete; remaining instrumentation coverage is optional and tracked for future enhancement rather than blocking closure.
