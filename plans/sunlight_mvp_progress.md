# Sunlight MVP Implementation Progress

Tracks execution of [sunlight_mvp_implementation_plan.md](sunlight_mvp_implementation_plan.md).

## Phase 1 — Core dose model ✅ (2026-05-24)

Pure-Kotlin logic in `:shared/commonMain`, no Android deps.

### Files added

- `shared/src/commonMain/kotlin/com/insola/uv/domain/Models.kt` — `GeoPoint`, `UvSample`, `UvForecast`, `ExposureInterval`, `Confidence`, `ManualExposureState`, `SkinSensitivity`.
- `shared/src/commonMain/kotlin/com/insola/uv/solar/SolarGeometry.kt` — closed-form NOAA-style solar elevation from `(lat, lon, Instant)`.
- `shared/src/commonMain/kotlin/com/insola/uv/dose/DoseIntegrator.kt` — trapezoidal integration over `UvForecast` samples with arbitrary `[from, to]` clipping and an `exposureFactor`. Returns UV-index-hours.
- `shared/src/commonMain/kotlin/com/insola/uv/dose/BurnModel.kt` — MED thresholds per `SkinSensitivity`. `timeToThreshold` does forward integration + quadratic interpolation of the linear-ramp crossing inside a segment.
- `shared/src/commonMain/kotlin/com/insola/uv/dose/VitaminDModel.kt` — UV-B proxy using solar elevation (cutoff at 30°, ramp to 1.0 at 60°) → categorical bucket.

### Tests added — `:shared/src/commonTest`

20 tests across 4 files; cover the cases called out in the plan plus a few edge cases.

- `DoseIntegratorTest` (7): flat UV=3 over 2 h = 6.0; nighttime = 0; rising vs falling mirrored curves give the same total area; exposure factor scales linearly; partial-segment clipping; out-of-range window = 0; monotonic accumulation.
- `BurnModelTest` (6): UV=3 + medium skin crosses MED at 70 min (3.5 / 3 h); night never burns; SPF delays burn; dark skin > fair skin time; alreadyAccumulated ≥ threshold returns `Duration.ZERO`; rising curve takes longer than mirrored falling curve from the same start.
- `SolarGeometryTest` (4): equatorial equinox noon ≈ 90°; equatorial midnight below horizon; Reykjavík winter noon stays under 5°; Berlin June solar noon > 55°.
- `VitaminDModelTest` (3): Reykjavík December = 0 score / `Bucket.None`; equatorial noon at high UV produces positive score; exposure fraction scales linearly.

### Checkpoint 1 — ✅ automated

```
JAVA_HOME=…/ms-17.0.19 ./gradlew :shared:allTests
BUILD SUCCESSFUL — 20 tests, 0 failures
```

### Notes / decisions

- Added `kotlinx-datetime 0.6.2` and `kotlin-test` to `:shared`; ran into the `withHostTest { }` requirement on the Android KMP target — added to `shared/build.gradle.kts` so `commonTest` actually compiles into `testAndroidHostTest`.
- `Instant.plus(Long, DateTimeUnit)` is awkward in 0.6.2 (TimeBased only); switched test fixtures to `Duration` arithmetic (`+ i.hours`) for readability.
- `ManualExposureState.toFactor()` is the single source of truth for translating user-asserted state into an exposure factor; SPF is modeled as `1/SPF` per the design doc.
- `BurnModel.solveCrossingHours` uses the quadratic formed by integrating a linear UV ramp; falls back to linear when slope ≈ 0.
- **Skin sensitivity uses the Fitzpatrick scale (I–VI)** rather than vague labels. MED thresholds in UV-index-hours derived from published per-type values in J/m² via the conversion 1 UV-index-hour ≈ 90 J/m² (1 UV-index unit ≈ 25 mW/m² erythemally weighted): I=2.2, II=2.8, III=3.9, IV=5.0, V=6.7, VI=11.1. `SkinSensitivity.Default = III`. Each enum carries a short behavioral description for UI use.

## Phase 1.5 — Synthetic-data dashboard

Not started.

## Phase 2 — Real UV + real location

Not started.

## Phase 3 — Manual corrections + timeline + persistence

Not started.

## Phase 4 — Passive estimation

Not started.

## Phase 5 — Polish

Not started.
