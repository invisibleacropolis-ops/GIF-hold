# Phase 4 ENGINEGUIDE Update Outline

## Objectives
- Capture the refactored UI package structure introduced across Phases 1–3 (layer/master feature packages, layout metrics, shared preview components).
- Document new reusable components so future contributors understand when to reach for `GifPreviewCard`, `BlendControlsCard`, or `LogPanelState`.
- Reflect coordinator/validation extractions completed in Phase 2 to keep architectural guidance accurate.
- Highlight render job registry and resources centralization so constants usage stays consistent.

## Proposed Structure Updates
1. **Section 2.2 – Repository Layout**
   - Update the tree to show `ui/components/preview`, `ui/layout`, `ui/resources`, and `ui/state/validation` directories.
   - Call out the new `docs/phase4` folder as a planning artifact (until documentation is merged).
2. **Section 3 – Application Architecture**
   - Add subsections describing coordinators (`ClipImporter`, `RenderScheduler`, `ShareCoordinator`) and how the view-model delegates to them.
   - Expand validation coverage to reference the extracted utilities and models.
3. **Section 4 – UI Surface Reference**
   - Refresh Layer and Master screen narratives to note shared preview scaffolds, blend control wrappers, and log panel state helper usage.
   - Add a dedicated subsection under 4.5 for `GifPreviewCard`, `PreviewPlacement`, and their adoption guidelines.
4. **Section 5 – Media & Storage Pipeline**
   - Mention the render job registry abstraction and how job IDs are assembled uniformly.
5. **Section 8 – Development Workflows**
   - Document testing strategy for validation utilities and coordinators (unit test entry points created in Phase 4).
6. **Appendix (Section 9)**
   - Ensure file-by-file reference links to new shared component files (`BlendControlsContent`, `GifPreviewCard`, etc.) and to the validation utility package.

## Callouts & Cross-References
- Link to relevant Kotlin files when describing shared components (e.g., `ui/components/preview/GifPreviewCard.kt`).
- Provide guidance on when to introduce new shared components vs. extending existing ones.
- Note persistent environment caveat about Android SDK requirement for tests (with instructions for local setup).
