# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & run

**JDK 17 is required.** Gradle 8.14 / AGP 9 do not support JDK 25 (JBR). Always invoke Gradle with `JAVA_HOME` pointing at a JDK 17 install:

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew :composeApp:assembleDebug
JAVA_HOME=/path/to/jdk-17 ./gradlew :shared:build
JAVA_HOME=/path/to/jdk-17 ./gradlew build
```

There are currently no tests; if/when added, use `./gradlew :shared:allTests` (KMP) or `:shared:testDebugUnitTest` / `:composeApp:testDebugUnitTest` (Android). Run a single test with `--tests "FQCN.method"`.

## Architecture

Two-module Kotlin Multiplatform + Compose Multiplatform project:

- `:shared` — KMP library (`com.android.kotlin.multiplatform.library` plugin) containing all Compose UI and ViewModels in `commonMain`. This is where feature code goes.
- `:composeApp` — Android application module. Holds only `MainActivity`, the manifest, and the Android entrypoint; depends on `:shared`.

**Why two modules:** AGP 9 forbids applying the KMP plugin to a `com.android.application` module. The Android app module cannot itself be KMP, so `:shared` exists to hold the multiplatform code and Compose UI, and `:composeApp` is a thin Android shell.

**iOS is stubbed.** `iosArm64`/`iosSimulatorArm64` targets are commented out in `shared/build.gradle.kts`. `shared/src/iosMain/` and `iosApp/` are placeholders. The Swift entrypoint expects `MainViewControllerKt.MainViewController()` from `shared`. Re-enable per `iosApp/README.md`.

**Note:** `composeApp/src/` contains stray `commonMain`/`androidMain`/`iosMain` directories alongside the active `main/` source set — they are leftovers and not wired into the Android app's source sets. Add Android-only code to `composeApp/src/main/`.

## Conventions

- Package root: `com.insola.uv` (shared namespace `com.insola.uv.shared`, app id `com.insola.uv`).
- DI is manual; introduce Koin only when a second injected dependency appears.
- No networking, persistence, or navigation libraries are wired yet — see README "Next steps" before adding (Ktor + open-meteo, Navigation Compose Multiplatform, SQLDelight/Room).
- Versions are centralized in `gradle/libs.versions.toml`; reference via `libs.*` accessors rather than hardcoding.
- **Prefer a functional style when reasonable.** Favor expressions over statements, immutable values over `var`, and Kotlin's collection operators (`map`, `filter`, `fold`, `sumOf`, `zipWithNext`, `runningFold`, etc.) over index-based `for` loops with mutable accumulators. Extract per-element logic into pure helpers so the top-level shape reads as a pipeline. Drop down to imperative loops only when the functional version is materially less clear, allocates unacceptably on a hot path, or needs early termination that doesn't fit `takeWhile`/`firstOrNull`.
