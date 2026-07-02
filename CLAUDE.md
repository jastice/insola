# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & run

**JDK 17 is required.** Gradle 9.5.1 / AGP 9.2.1 do not support JDK 25 (JBR). Always invoke Gradle with `JAVA_HOME` pointing at a JDK 17 install:

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew :composeApp:assembleDebug
JAVA_HOME=/path/to/jdk-17 ./gradlew :shared:build
JAVA_HOME=/path/to/jdk-17 ./gradlew build
```

Tests live in `shared/src/commonTest/` and run on the JVM via the Android host-test target. Use `./gradlew :shared:testAndroidHostTest` (or `:shared:allTests` to include any other KMP targets that may get re-enabled later). Run a single test with `--tests "FQCN.method"`. Note: there is **no** `:shared:testDebugUnitTest` task under the `com.android.kotlin.multiplatform.library` plugin — that name fails with "task not found".

## Architecture

Two-module Kotlin Multiplatform + Compose Multiplatform project:

- `:shared` — KMP library (`com.android.kotlin.multiplatform.library` plugin) containing all Compose UI and ViewModels in `commonMain`. This is where feature code goes.
- `:composeApp` — Android application module. Holds only `MainActivity`, the manifest, and the Android entrypoint; depends on `:shared`.

**Why two modules:** AGP 9 forbids applying the KMP plugin to a `com.android.application` module. The Android app module cannot itself be KMP, so `:shared` exists to hold the multiplatform code and Compose UI, and `:composeApp` is a thin Android shell.

**iOS is stubbed.** `iosArm64`/`iosSimulatorArm64` targets are commented out in `shared/build.gradle.kts`. `shared/src/iosMain/` and `iosApp/` are placeholders. The Swift entrypoint expects `MainViewControllerKt.MainViewController()` from `shared`. Re-enable per `iosApp/README.md`.

**Note:** the Android app module's only source set is `composeApp/src/main/` — add Android-only code there.

**Live vs. dev mode.** The dashboard is driven by a neutral day-model, `domain/UvDay.kt` (location + local-midnight `dayStart` + 25 hourly UV samples + hour↔instant helpers). Both sources produce one:
- **Live** (default): the dashboard opens **immediately** on a network-free clear-sky **estimate** (`UvDay.clearSkyEstimate`, synthesized from solar geometry at the device-timezone city), then upgrades in place to the real Open-Meteo forecast once it loads. "Now" tracks the real wall clock (`DashboardViewModel` ticks ~1/min); the curve scrubber is a **preview-only** marker decoupled from now. There is **no blocking load screen** — loading and any fetch error surface as an inline status row (small spinner / "Retry"); a failed fetch just leaves the estimate on screen (`DayMode.LiveEstimate`).
- **Dev**: synthetic `dev/Fixtures.kt` scenarios (`DayMode.Fixture`), with scrubber-as-now. Reached only via a hidden long-press on the card header (debug builds, `devMode`), which reveals a scenario picker + a "Live forecast" chip.

`DashboardCompute.compute(day, now, previewHour, …)` is the pure pipeline all modes share; a `compute(day, hour, …)` overload gives scrubber-as-now for tests. `DashboardViewModel` exposes a single always-renderable `DashboardUiState` (dashboard + `DayMode` + inline `refreshing`/`error`).

**Networking** (`data/OpenMeteoUvForecastProvider.kt`): Ktor + kotlinx-serialization against Open-Meteo's keyless Air-Quality API (HTTPS). The `OkHttp` engine + `HttpClient` are built in `MainActivity` (manual DI) and injected down.

**Location** (`location/LocationProvider.kt`, commonMain interfaces): a `ChainedLocationProvider` falls through fresh Fused fix → last-known → IP-geo (ipwho.is) → timezone-centroid (always succeeds), each tagged with a `LocationSource`. Each resolved location also carries a human `place` name shown as the dashboard header: IP-geo returns the city directly, the timezone strategy derives it from the zone id, and device fixes are named via a `ReverseGeocoder` (Android `Geocoder` impl, `AndroidReverseGeocoder`). Non-GPS sources add an "Approximate · …" precision caption under the header. The Android Fused impl is `shared/src/androidMain/.../location/FusedDeviceLocationSource.kt` (permission-checked, `withTimeoutOrNull` so a hung GPS never blocks launch). Background collection is structured for but not built.

## Conventions

- Package root: `com.insola.uv` (shared namespace `com.insola.uv.shared`, app id `com.insola.uv`).
- DI is manual — constructor injection from `MainActivity` → `App()` → `DashboardViewModel` (the location stack needs an Activity `Context`). Introduce Koin only if the wiring grows materially.
- Networking is wired (Ktor + kotlinx-serialization → Open-Meteo). Persistence and navigation are **not** — see README "Next steps" before adding (Navigation Compose Multiplatform, SQLDelight/Room). Offline caching is not yet built either.
- Versions are centralized in `gradle/libs.versions.toml`; reference via `libs.*` accessors rather than hardcoding.
- **Prefer a functional style when reasonable.** Favor expressions over statements, immutable values over `var`, and Kotlin's collection operators (`map`, `filter`, `fold`, `sumOf`, `zipWithNext`, `runningFold`, etc.) over index-based `for` loops with mutable accumulators. Extract per-element logic into pure helpers so the top-level shape reads as a pipeline. Drop down to imperative loops only when the functional version is materially less clear, allocates unacceptably on a hot path, or needs early termination that doesn't fit `takeWhile`/`firstOrNull`.
