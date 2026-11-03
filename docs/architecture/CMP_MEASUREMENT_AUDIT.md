# CMP Measurement Foundation audit — stage 1

> **Історичний pre-change аудит PR #66, completed.** Read-only CMP Details merged; Windows JDBC RETURNING repaired in merged PR #68. Не повторювати цей аудит як невиконане завдання. Для нових створень/редагувань застосовувати [план PR A/B](../CMP_MEASUREMENT_EDITOR_PLAN.md); власник скасував вимогу сумісності зі старими тестовими кресленнями, але чинні контракти нових замірів залишаються.

Base: main `0dfcbf9`, merged PR #65 (final head `a36d979`). Reviewed Issues
#60/#57, README, roadmap, Android editor/drawing/codec and their consumers,
FrozenPolygonGeometry, MeasurementRepository/queries, ObjectsClientsStore,
ProjectDetailsScreen, AppNavigationState and Android/Desktop hosts before changes.

## Reuse and boundaries

- Reuse FrozenPolygonGeometryContract, CeilingShapeTemplates, exact Long-mm
  vertices/sides, OrthogonalSideContour and existing precision/geometry validation.
  Do not change calculations, stored totals or supported bounds (±1,000,000mm).
- Measurements already have projectId, shape, dimensions, drawingJson, notes and
  soft deletion. Existing SQLDelight queries support by-ID and 1:N reads; no
  schema/migration is needed. Add reactive by-ID/ownership reads through these
  repositories rather than platform DB rules.
- v2 measurement fields: shape, geometrySchemaVersion, vertices, measurementsMm,
  areaSquareMeters, perimeterMeters, precisionStepMeters. v3 adds measured sides
  (lengthMm/turnAfter). No automatic JSON rewrite on reading.
- Android FrozenPolygonGeometryJson also handles immutable estimate/PDF source
  snapshots (different field set and externally supplied precision). Keep that
  path untouched; measurement read/write delegates to a common codec using the
  existing kotlinx-serialization-json dependency. All editor/calculator callers
  keep their existing adapter entry points.
- Android MeasurementDrawingPreview is used by the list, editor and calculator.
  Introduce and verify a common Canvas component for new details on both hosts.
  Keep the actively used Android renderer until owner-verified editor parity. Keep the editor/forms,
  creation/deletion/global Measurements tab and calculator behavior intact.
- Historical rectangle `{}` rows have saved REAL length/width and totals, but no
  captured precision. Render an explicitly historical dimension-only outline;
  display stored totals without recalculating/overwriting them or inventing a
  precision. Corrupt nonempty geometry must not silently fall back to rectangle.
- Extend the current route state with numeric project+measurement IDs, validate
  ownership against real data, and keep Project Details mounted but inactive
  while viewing a measurement. This preserves its list/animation/snap state;
  cancel pending settle work when inactive. Preserve Objects tab behavior.
- Use CeilingsScreenScaffold/PinnedContent/Fab. Host navigation and system Back/
  Escape remain adapters; live predictive preview #56 and runnable iOS #42 remain
  separate work. No create/edit/+ route in this stage.

## Historical verified PR #65 CI baseline (head a36d979)

| Platform | Successful run | Job wall time (queue excluded) | Slowest build step |
| --- | --- | --- | --- |
| Android | 41533897104 | 11m26s | SQLCipher emulator 4m53s; shared build 2m02s, Release 1m34s |
| Windows | 41533897297 | 2m59s | Desktop/JVM/CRUD 1m51s |
| iOS/macOS | 41533897297 | 14m17s | framework 9m17s, Simulator 2m16s, JVM/Desktop 57s |

Final PR compares its own jobs/cache outcomes to this actual final PR #65 base,
not the earlier cold run at 840d2fb. No extra CI invocation/dependency/toolchain
or workflow change is planned. All existing platform/encrypted tests remain.

Baseline cache logs: all three jobs restored Gradle home via a fallback key;
Android clean AVD hit. macOS still downloaded Native/LLVM dependencies, so a Gradle
hit alone does not prove a warm Kotlin/Native toolchain. Final comparison records
these states separately.
