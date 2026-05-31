# Real User Mode — Live UV Forecast at Current Location

## Context

Today the app is a **dev simulator**: the dashboard is hardwired to `Fixtures` — six synthetic `Scenario`s whose UV curves are generated from solar geometry. No real data, no real location, and "now" is wherever the user drags the curve scrubber. A placeholder `UvForecastProvider` interface and `FakeUvForecastProvider` already anticipate this work.

This change turns it into a **real app for one user**: on launch it resolves the device's location, fetches today's hourly UV forecast from Open-Meteo, and shows the live curve. Outdoor-time tracking stays manual (the existing `OutdoorSession` toggle). Fixtures stay for tests and behind a hidden dev gesture.

**Confirmed product decisions:**
1. Live data is *the* app; the scenario picker is hidden, revealed only by a secret long-press gesture.
2. Location via Play Services **FusedLocationProviderClient** (to enable future background collection; v1 is foreground-only).
3. When no fix is available, **guess** the location via a fallback chain and always show *something*, noting which source was used.
4. **"Now" tracks the real wall clock.** The scrubber becomes a preview-only marker; outdoor toggles stamp `Clock.System.now()`.

## Goal

On launch, fetch today's hourly UV curve at the device's current (or best-guess) location and drive the existing dashboard from it in real time, with manual outdoor-session tracking — while keeping all existing dose/burn/vitamin-D/sunscreen math intact.

## Approach

Reuse the entire `DashboardCompute` pipeline by introducing a neutral **`UvDay`** day-model (the data shape `Scenario` already is, minus dev metadata) that both `Fixtures` and a new live provider produce. Exploit existing redundancy in `DashboardState` (it already has both `now` and `hourOfDay`) to **decouple real time from the scrubber**: `now` becomes a real-clock input, `hourOfDay` a preview-only marker. Add a thin Ktor + kotlinx-serialization networking layer and a Play-Services location layer (commonMain interfaces, Android impls in a new `androidMain` source set), wired by manual constructor injection from `MainActivity` → `App()` → `DashboardViewModel`. Staged so the **risky refactors land first, behind green tests, with zero behavior change**, before any networking/location.

## File Changes

**Domain / model bridge**
- **Create** `shared/.../domain/UvDay.kt` — `location`, `dayStart`, `hourlyUv[25]`, plus `forecast`, `hourToInstant`, `instantToHour`, `dayEnd` (moved from `Scenario`); `fromForecast(forecast, zone, clock)` derives local-midnight `dayStart` and resamples 25 nominal slots via `UvForecast.uvAt`.
- **Modify** `dev/Fixtures.kt` — `Scenario` keeps id/name/description + curve synthesis, exposes `val day: UvDay`.

**now/scrubber decoupling**
- **Modify** `dashboard/DashboardCompute.kt` — `compute(day, now: Instant, previewHour, profile, sessions, attenuation)`; delete the `now = hourToInstant(hourOfDay)` derivation; keep a `compute(day, hour, …)` overload so tests stay valid.
- **Modify** `dashboard/DashboardViewModel.kt` — `scenario`→`day`; ~1/min `nowFlow`; live mode feeds real clock into `now`, dev mode feeds `hourToInstant(previewHour)`; toggles stamp `Clock.System.now()`; fold inputs into bundles to stay within `combine` arity.
- **Modify** `DashboardState` — `scenario`→`day`; `hourOfDay`→`previewHour`; add `nowHour`.
- **Modify** `dashboard/UvCurveChart.kt` (tightest coupling) — split the single marker into a solid **now** marker (`nowHour`) and a dashed draggable **preview** marker (`previewHour`); use `day.*`; open-session band ends at `nowHour`.
- **Modify** `dashboard/SkinSummary.kt`, `data/FakeUvForecastProvider.kt` — consume `UvDay`.

**Networking**
- **Modify** `gradle/libs.versions.toml`, `shared/build.gradle.kts` — add Ktor (core/content-negotiation/json/okhttp), kotlinx-serialization-json + plugin, play-services-location; add the missing `androidMain` source-set block.
- **Create** `data/OpenMeteoUvForecastProvider.kt` — `@Serializable` DTOs + Ktor GET to the Air-Quality API; returns `UvForecast`.

**Location**
- **Create** `location/LocationProvider.kt` (commonMain) — `LocationSource` enum, `ResolvedLocation`, `LocationProvider` (never throws), `ChainedLocationProvider`, `IpLocationProvider` (keyless HTTPS), `TimezoneLocationProvider`, `DeviceLocationSource` interface.
- **Create** `shared/src/androidMain/.../location/FusedDeviceLocationSource.kt` — Fused last-known + current fix, permission-checked, `withTimeoutOrNull`.

**Wiring & UI states**
- **Modify** `App.kt` — accept `forecastProvider`, `locationProvider`, `devMode`; pass into ViewModel factory.
- **Modify** `MainActivity.kt` — build `HttpClient`, Fused source, chained provider, Open-Meteo provider; permission launcher that calls `vm.refresh()` on grant.
- **Modify** `AndroidManifest.xml` — add `INTERNET`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`.
- **Modify** `dashboard/DashboardScreen.kt` — `DayPhase` (Loading/Loaded/Error) switch; location-source notice; long-press-revealed `ScenarioPicker` + "Live" chip.

## Implementation Steps

1. **Extract `UvDay`** (no behavior change): create it, migrate Compute/State/Chart/SkinSummary/Fake, add `Scenario.day` + `compute(day,hour,…)` overload, update tests to `Fixtures.byId(id).day`. Build + `:shared:testDebugUnitTest` green.
2. **Decouple now/scrubber**: add `now`/`previewHour` to `compute`, add `nowHour`, split chart markers, add `nowFlow` + live/dev branch. Fixtures keep scrubber-as-now. New decoupling test. Green.
3. **Build config**: catalog + serialization plugin + `androidMain` block + manifest perms. Confirm Ktor 3.x / kotlinx-serialization versions vs Kotlin 2.3.21 + CMP 1.11.0. Sync green.
4. **Networking**: `OpenMeteoUvForecastProvider` + DTOs + `UvDay.fromForecast`; `HttpClient(OkHttp)` in MainActivity. Parse/dayStart/DST tests.
5. **Location**: commonMain interfaces + chain + IP + timezone; `FusedDeviceLocationSource` with timeouts. Chain-ordering test.
6. **Wiring & states**: ViewModel ctor injection + `refresh()` + `DayPhase`; `App()` params; MainActivity deps + permission launcher; DashboardScreen Loading/Error/Loaded + source notice.
7. **Hidden dev gesture**: remove always-on picker; long-press "UV today" title reveals `ScenarioPicker` + "Live" chip.
8. **Manual run** on a device/emulator with Play Services.

## Key Reuse / Design Notes

- **DST-safe day building:** `today.atStartOfDayIn(zone)` + resample 25 nominal slots through the tested `UvForecast.uvAt` — never slice 25 raw array entries.
- **Open-Meteo:** `https://air-quality-api.open-meteo.com/v1/air-quality?latitude=..&longitude=..&hourly=uv_index&timezone=auto&start_date=&end_date=` (today), keyless, HTTPS.
- **IP-geo fallback:** ipwho.is primary (keyless HTTPS), ipapi.co alt. Both HTTPS — no cleartext.
- **Fallback chain:** fresh Fused fix → last-known → IP-geo → timezone-centroid (always succeeds), each tagged with `LocationSource`.
- **Manual DI:** constructor injection from `MainActivity` (deps need Activity `Context`); no expect/actual factory, no Koin.

## Acceptance Criteria

- Launch with permission granted shows today's real UV curve; chart peak matches Open-Meteo's daily peak (±0.1).
- Live now-marker sits at current wall-clock hour and advances (~1 min); dragging the scrubber moves only the preview marker and does not change current-UV readout, dose, or time-to-burn.
- "I'm outside" creates a session stamped at real `Clock.System.now()`; dose integrates only over logged sessions up to real now.
- Permission denied still shows a forecast (last-known/IP/timezone) + a notice naming the source; Retry/grant upgrades to GPS without restart.
- Forecast/location failure shows an error state with working Retry.
- Long-press "UV today" reveals fixtures picker; selecting a fixture restores scrubber-as-now; "Live" chip returns to live mode.
- `:shared:testDebugUnitTest` passes incl. new tests (decoupling, Open-Meteo parse+slice, DST dates, fallback ordering).

## Verification

```
JAVA_HOME=/path/to/jdk-17 ./gradlew :shared:testDebugUnitTest
JAVA_HOME=/path/to/jdk-17 ./gradlew :composeApp:assembleDebug
```
Unit: (1) `compute(day, now=N, previewHour=P)` with P≠hour(N) → readouts track N, invariant to P. (2) decode captured Open-Meteo JSON → 25 samples, slot 0 == 00:00, peak correct, null→0.0. (3) DST spring-forward/fall-back → exactly 25 slots, no throw. (4) fallback chain fakes → Gps>LastKnown>Ip>Timezone.
Manual (emulator with Play Services): grant → live curve; deny → fallback + notice; toggle outside → dose accrues; drag scrubber → live numbers don't move; long-press → fixtures.

## Risks & Mitigations

- **now/scrubber decoupling touches many UI sites** → land Tasks 1–2 first behind the new Compute test, before networking.
- **DST 23/25-hour days** → `atStartOfDayIn` + resample via `uvAt`; explicit DST-date tests.
- **Play Services missing / location hangs** → `withTimeoutOrNull(5s)` so chain falls through to IP-geo; never block launch.
- **Ktor / serialization version drift** → confirm compatible versions before locking the catalog.
- **`combine` arity** (5-arg max) → fold ViewModel inputs into bundle data classes.

## Non-goals (v1)

Background location collection (structured for, not built), offline caching/persistence, manual city/coordinate entry, multi-day forecasts, iOS.