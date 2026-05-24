# Sunlight MVP Implementation Plan

Companion to `sunlight_mvp_design_doc.md`. Phased plan with manual-verification checkpoints, tuned to the current skeleton (`:shared` KMP library + `:composeApp` Android shell, no networking/persistence yet).

## Phase 1 — Core dose model (shared, no UI changes)

Pure-Kotlin logic in `:shared/commonMain`, no Android deps.

- `domain/` models: `GeoPoint`, `UvSample`, `UvForecast`, `ExposureInterval`, `Confidence`, `ManualExposureState`, `SkinSensitivity`.
- `solar/SolarGeometry.kt`: solar elevation from lat/lon/time (closed-form NOAA approximation).
- `dose/DoseIntegrator.kt`: discrete integration over `UvForecast` samples with an `exposureFactor`. Returns UV-index-hours.
- `dose/BurnModel.kt`: thresholds per `SkinSensitivity` preset → `timeToThreshold(now, forecast, assumedFactor)` via forward integration + linear interpolation of crossing.
- `dose/VitaminDModel.kt`: UVB proxy from UV index + solar elevation + saturation curve → categorical bucket.
- Unit tests with synthetic curves (flat UV=3 for 2h; rising vs falling curve; nighttime zero).

**Checkpoint 1 (automated):** `./gradlew :shared:allTests` green. No UI to inspect yet — purely the math.

## Phase 1.5 — Synthetic-data dashboard (dev mode)

UI-complete dashboard against in-memory fixtures. No network, no Fused Location, no DB.

- `data/UvForecastProvider` interface in `:shared/commonMain` with a `FakeUvForecastProvider` implementation.
- `dev/Fixtures.kt`: a handful of canned scenarios, each combining a synthetic location + UV curve:
  - **Equatorial noon** — Singapore, clear-sky peak UV 12 curve.
  - **Mid-latitude summer** — Berlin, peak UV 7, asymmetric.
  - **Winter low** — Reykjavík, peak UV 1.
  - **Cloudy afternoon** — flat UV 3 morning, drop to 1 after 14:00.
  - **Rising vs falling** — two curves with the same area but mirrored, to make time-integration visible.
- A simulated "now" clock the user can scrub (slider over the day) so the dashboard's accumulated dose, current UV, and time-to-burn update without waiting for real time.
- Dev-mode toggle: settings screen (or long-press on header) that lets you pick scenario + scrub time. Off by default; in release builds, hide behind a `BuildConfig.DEBUG` check.
- Wire the real dashboard composables and ViewModel against `UvForecastProvider`, with the fake injected manually. No production provider yet.

**Checkpoint 1.5 (manual):** Launch app, enter dev mode, cycle through scenarios. Verify:

- Equatorial noon shows short time-to-burn and "likely sufficient" vit-D quickly.
- Reykjavík winter shows very long / no burn time and "very low" vit-D all day.
- Scrubbing the clock from 06:00 → 20:00 makes accumulated dose climb monotonically and time-to-burn shrink then grow as the sun sets.
- The two mirrored curves give the *same* end-of-day dose but *different* time-to-burn at the same clock time — proves time-integration is doing real work.
- Changing sensitivity preset rescales budget % and time-to-burn coherently.

This is the UX-honesty gate: if the numbers feel wrong here, they'll feel wrong with real data too, and it's much cheaper to fix now.

## Phase 2 — Real UV + real location

Swap implementations behind the same interfaces. UI already trusted.

- Open-Meteo `UvForecastProvider` implementation (Ktor). Free, no key, hourly `uv_index`.
- Fused Location one-shot at launch; manual lat/lon override still available.
- Keep dev mode as a toggle — invaluable for debugging later phases.

**Checkpoint 2 (manual):** Install on device/emulator; allow location; see live UV for your area, the day's curve, and a plausible "time to burn" that shrinks as the sun gets stronger. Switch sensitivity preset — time-to-burn responds. Override location to a high-UV latitude — values change. Dev mode still works as a fallback.

## Phase 3 — Manual corrections + timeline + persistence

Make the model inspectable and correctable before adding passive inference.

- SQLDelight schema: `manual_correction`, `exposure_evidence`, `exposure_interval`, `daily_summary`, `uv_forecast_cache`.
- Correction events: indoors / shade / sunscreen / covered / undo. Each is an interval with open end until superseded.
- `ExposureTimelineReducer` in `:shared`: reduces evidence + corrections → `List<ExposureInterval>` for today.
- Timeline screen: chronological strip of intervals with factor and dose contribution.
- Dashboard dose reflects corrections, not naive continuous-outdoor.

**Checkpoint 3 (manual):** Tap "indoors" for an hour, then "outside" — dose accumulation pauses and resumes; timeline shows the gap. Apply sunscreen — time-to-burn jumps out. Kill app, relaunch — state persists.

## Phase 4 — Passive estimation

Only once the manual model feels honest.

- Background location (significant change + periodic WorkManager).
- Activity Recognition (still / walking / in vehicle).
- Ambient light when foregrounded.
- `OutdoorProbabilityHeuristic` in `:shared` consuming normalized `ExposureEvidence`.
- Automatic interval generation, manual corrections still override.
- Permission onboarding flow (background location rationale).

**Checkpoint 4 (manual):** Walk outside for 15 min, then sit indoors — timeline shows an outdoor interval followed by indoor without manual input. Confidence drops when location is stale.

## Phase 5 — Polish

Notifications (budget near, vit-D reached), data deletion, defaults tuning, confidence copy.

**Checkpoint 5 (manual):** Cross 80% of budget → notification fires once. Settings → delete-all clears DB.

## Early decisions

- **UV provider:** Open-Meteo for MVP (free, no key, hourly UV). OpenUV needs a key and has request limits.
- **Unit:** keep UV-index-hours internally; never show raw to user.
- **Module layout:** keep current 2-module layout (`:shared`, `:composeApp`) for MVP. Split into the doc's 4-module layout only if compile times or KMP boundaries demand it — premature for the skeleton.
- **iOS:** stay stubbed; keep all model code free of Android imports so re-enabling iOS targets later is mechanical.
