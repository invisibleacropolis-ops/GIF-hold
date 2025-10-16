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
