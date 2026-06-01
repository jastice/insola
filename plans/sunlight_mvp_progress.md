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

## Phase 1.5 — Synthetic-data dashboard ✅ (2026-05-24)

UI-complete dashboard backed by in-memory fixtures and a scrubbed clock. No network or persistence yet.

### Files added

- `shared/src/commonMain/kotlin/com/insola/uv/data/UvForecastProvider.kt` — interface for Phase 2.
- `shared/src/commonMain/kotlin/com/insola/uv/data/FakeUvForecastProvider.kt` — wraps a Scenario.
- `shared/src/commonMain/kotlin/com/insola/uv/dev/Fixtures.kt` — `Scenario` plus six canned scenes: equatorialNoon (Singapore), berlinSummer, reykjavikWinter, cloudyAfternoon (Paris), and mirrorRising / mirrorFalling (the same hourly curve and its reverse — same EOD dose, very different midday accumulation).
- `shared/src/commonMain/kotlin/com/insola/uv/domain/UvForecastExt.kt` — `UvForecast.uvAt(time)` for the current-UV readout.
- `shared/src/commonMain/kotlin/com/insola/uv/dashboard/DashboardCompute.kt` — pure derivation of `DashboardState` from `(scenario, hourOfDay, sensitivity)`. Lives outside the ViewModel so it's testable without a coroutine scope.
- `shared/src/commonMain/kotlin/com/insola/uv/dashboard/DashboardViewModel.kt` — `combine(scenarioId, hourOfDay, sensitivity).stateIn(...)` over `DashboardCompute.compute`.
- `shared/src/commonMain/kotlin/com/insola/uv/dashboard/DashboardScreen.kt` — Material3 composable: scenario chip row, current UV + solar elevation, UV budget meter, time-to-burn, vit-D bucket, time scrubber (0–24 h, 15-min increments), Fitzpatrick chip picker.
- Deleted the old `UvViewModel.kt` placeholder; `App.kt` now hosts `DashboardScreen`.

### Tests added — `DashboardComputeTest` (5)

Drives `DashboardCompute.compute` directly to verify every Checkpoint 1.5 claim from the plan:

- **Equatorial noon** at hour 12, Type III → current UV > 8, time-to-burn < 60 min, vit-D ≥ Adequate.
- **Reykjavík winter** at hour 23.5, Type II → time-to-burn null, vit-D None. (Curve total ~2.0 UV-idx·h is tuned to stay under Type I's 2.2 MED so even continuous outdoor exposure across the full day does not trigger a burn warning.)
- **Monotonic accumulation** — sweeping hour 0→24 in Berlin produces a non-decreasing accumulated-dose series.
- **Mirrored curves** — rising and falling scenarios have identical EOD dose but the falling curve has > 2× the rising curve's accumulated dose at hour 12. (Tested via accumulated dose rather than time-to-burn because both curve totals far exceed every MED, so time-to-burn ties at 0 at noon and obscures the underlying signal.)
- **Skin sensitivity rescaling** — at hour 7 in Berlin (accumulated ≈ 1.8 UV-idx·h, below both bounds), Type I uses a higher budget % and a shorter time-to-burn than Type VI.

### Checkpoint 1.5 — ✅ automated

Plan calls this a manual gate. Drove it automatically via `DashboardComputeTest` because the UI just renders the values the test pins. All 5 dashboard tests + the 20 Phase 1 tests = **25 tests green**. `:composeApp:assembleDebug` builds cleanly.

Manual smoke (eyeballing the live UI on an emulator/device) is still worth doing once before Phase 2 to verify slider feel, chip layout, and that nothing crashes — but the numerical claims are now under test.

### Notes / decisions

- Compute is pure and synchronous (the dose math is cheap) — `combine(...).stateIn(Eagerly, initial)` is enough; no `viewModelScope` work happens off the calling thread.
- `Fixtures.Scenario` stores `dayStart` as the `Instant` of midnight **local** at the location so `SolarGeometry` returns realistic elevations when keyed by `dayStart + hourOfDay.hours`. Each scenario carries a comment noting the UTC offset used.
- The dev dashboard owns scenario selection directly; `FakeUvForecastProvider` is wired up for Phase 2 / 3 use and isn't yet on the hot path.
- Phase 1.5 makes the dashboard *always* show the dev controls. The plan calls for a `BuildConfig.DEBUG` gate; that lands in Phase 2 when the real provider arrives.
- `skinExposedFraction` is hardcoded to 0.25 in the compute (rough "arms + face + neck"). User-controllable in a later phase.

## Phase 1.6 — UV-over-day chart + outdoor session log ✅ (2026-05-25)

Replaces the "continuous outdoor all day" assumption with an explicit log of outdoor sessions, and adds a visual UV projection chart so the user can see what the day looks like.

### Files added

- `shared/src/commonMain/kotlin/com/insola/uv/domain/OutdoorSession.kt` — `data class OutdoorSession(start, end?)`. `end = null` ⇒ session is open (currently outside). `effectiveEnd(now)` and `toInterval(now)` make conversion to `ExposureInterval` explicit.
- `shared/src/commonMain/kotlin/com/insola/uv/dashboard/UvCurveChart.kt` — Compose Canvas: filled area under the hourly UV curve, primary-color outline, "now" vertical marker, tertiary-color shaded bands per logged session, 0/6/12/18/24 hour ticks below.

### Files changed

- `shared/src/commonMain/kotlin/com/insola/uv/dose/VitaminDModel.kt` — added `accumulateOverIntervals` and `bucketForIntervals` (each interval folds its own `accumulate` with `skinExposedFraction * exposureFactor`).
- `shared/src/commonMain/kotlin/com/insola/uv/dashboard/DashboardCompute.kt` — now integrates **only** over logged sessions (clipped to `[dayStart, now]`). Vit-D follows the same intervals. Time-to-burn unchanged: still projects "if you were outside continuously from now" — useful as a what-if regardless of current status.
- `shared/src/commonMain/kotlin/com/insola/uv/dashboard/DashboardViewModel.kt` — added `sessionsFlow`, `toggleOutside()`, `clearSessions()`, `removeSession(index)`. Switching scenarios clears the log (sessions are anchored to a scenario's day).
- `shared/src/commonMain/kotlin/com/insola/uv/dashboard/DashboardScreen.kt` — added `UvProjectionCard` (hosts the chart) and `OutdoorLogCard` (toggle button + session list with per-row remove + bulk Clear).

### Tests added — `DashboardComputeTest` extensions (4 new, 9 total)

- **`emptyLog_meansNoAccumulatedDose_andNoVitD`** — no logged time ⇒ 0 dose, `Bucket.None`. Time-to-burn still resolves (it's a hypothetical).
- **`openSession_accruesDoseUntilNow`** — open session 10→null at hour 12 matches a closed 10→12 session exactly; `isCurrentlyOutside` flagged only for the open one.
- **`shorterSession_yieldsLessDose`** — 1 h vs 4 h around solar noon: longer session ⇒ more dose.
- **`futureSession_doesNotContribute`** — session scheduled for hours 14–16 with now=10 contributes 0.

Existing 5 tests rewritten to inject a `fullDaySession()` helper so their "continuous outdoor" semantics survive the model change. **29 tests, all green.**

### Notes / decisions

- **Semantic shift:** accumulated dose and vit-D now reflect *actually logged* time outside, not continuous outdoor exposure. This is the honest accounting the app eventually wants; the continuous assumption was a placeholder.
- Time-to-burn intentionally keeps the projection semantics ("if you went outside now") — it's still useful when you're inside debating whether to head out, and it's the natural prompt for the upcoming "go outside?" UX.
- Switching scenarios clears the log because session `Instant`s are bound to the previous scenario's `dayStart`. Cheaper than translating them.
- The chart uses raw `hourlyUv` for shape (not the interpolated `uvAt`) — the hourly samples are the source of truth and look identical at chart resolution.

## Phase 2 — Real UV + real location ✅ (2026-05-31)

Turned the dev simulator into a real single-user app: on launch it resolves the device location, fetches today's hourly UV curve from Open-Meteo, and drives the existing dashboard live. See [`.air/plans/real-user-mode-live-uv.plan.md`](../.air/plans/real-user-mode-live-uv.plan.md).

### Day-model bridge + now/scrubber decoupling (no behavior change)

- `shared/.../domain/UvDay.kt` — neutral day-model (location, local-midnight `dayStart`, 25 hourly samples, `forecast`/`hourToInstant`/`instantToHour`/`dayEnd`, moved off `Scenario`). `fromForecast` anchors local midnight via `atStartOfDayIn` and resamples 25 nominal slots through `UvForecast.uvAt` (DST-safe). `Scenario` now wraps a `UvDay` + dev metadata.
- `DashboardCompute.compute(day, now, previewHour, …)` anchors every readout/integral to real `now`; a `compute(day, hour, …)` overload preserves scrubber-as-now for tests. `DashboardState` gained `previewHour` + `nowHour`.
- `UvCurveChart` splits the single marker into a solid **now** marker and a dashed draggable **preview** marker; open-session bands end at `nowHour`.

### Networking

- `data/OpenMeteoUvForecastProvider.kt` — Ktor + kotlinx-serialization against the keyless Air-Quality API (HTTPS). `@Serializable` DTOs reattach `utc_offset_seconds` to each local timestamp; `null` UV → 0.0.

### Location

- `location/LocationProvider.kt` (commonMain) — `LocationSource`, `ResolvedLocation`, never-throwing `LocationProvider`, `ChainedLocationProvider` (fresh fix → last-known → IP-geo → timezone-centroid), `IpLocationProvider` (ipwho.is), `TimezoneLocationProvider`, `DeviceLocationSource`.
- `shared/src/androidMain/.../location/FusedDeviceLocationSource.kt` — Fused current/last-known fix, permission-checked, `withTimeoutOrNull` so a hung GPS never blocks launch.

### Wiring & states

- `DashboardViewModel` takes injected `UvForecastProvider` + `LocationProvider` + `devMode`; exposes `DashboardUiState` (Loading / Error+retry / Ready), loads live on launch + `refresh()`, ticks "now" ~1/min, stamps outdoor toggles at real `Clock.System.now()` in live mode.
- `MainActivity` builds the `OkHttp` `HttpClient`, the provider chain, and the VM (factory), and registers a location-permission launcher that calls `refresh()` on grant (upgrades to GPS without restart). `App()` takes the VM + `devMode`.
- `DashboardScreen` switches on phase, shows a location-source notice, and reveals the fixture picker + "Live" chip only via a long-press on the "UV today" title (debug builds).
- Build config: added Ktor (core/content-negotiation/json/okhttp), kotlinx-serialization (+ plugin), play-services-location, the `androidMain` source set, coroutines-test, and `INTERNET` / `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` permissions.

### Checkpoint 2 — ✅ automated

```
JAVA_HOME=…/jdk-17 ./gradlew :shared:testAndroidHostTest   # 96 tests, 0 failures
JAVA_HOME=…/jdk-17 ./gradlew :composeApp:assembleDebug      # BUILD SUCCESSFUL
```

New tests: now/scrubber decoupling (readouts track `now`, invariant to preview); Open-Meteo JSON decode (25 samples, slot 0 at local midnight, peak, null→0.0); DST spring-forward / fall-back day building; fallback-chain ordering (Gps > LastKnown > Ip > Timezone). **Manual on-device run (emulator with Play Services) still pending** — couldn't run hardware here.

### Notes / decisions

- The test task is `:shared:testAndroidHostTest` (the KMP-library plugin has no `:shared:testDebugUnitTest`).
- "Now" tracks the real wall clock; the scrubber is preview-only. Dose/burn/vit-D integrate only over logged sessions up to real now — dragging the scrubber never moves the live numbers.
- The chain always resolves *something* (timezone centroid is terminal), so the app shows a forecast even with permission denied / no Play Services, noting which source was used.
- Manual constructor DI from `MainActivity` (the location stack needs an Activity `Context`); no Koin, no expect/actual factory.

## Phase 3 — Manual corrections + timeline + persistence

Not started.

## Phase 4 — Passive estimation

Not started.

## Phase 5 — Polish

Not started.
