# insola

Kotlin Multiplatform UV-tracking app. Android first, iOS to follow.

## Stack

- Kotlin 2.3.21, Gradle 8.14+, AGP 8.11
- Compose Multiplatform 1.11 (shared UI in `commonMain`)
- AndroidX Lifecycle ViewModel (multiplatform)
- JDK 17 toolchain
- DI: manual (add Koin when wiring grows)
- Networking / persistence / navigation: not wired yet

## Layout

```
shared/             # KMP library: Compose UI + ViewModel
  src/commonMain/
  src/iosMain/      # iOS entrypoint factory (stubbed)
composeApp/         # Android app: MainActivity + manifest, depends on :shared
  src/main/
iosApp/             # Xcode wrapper (stubbed — see iosApp/README.md)
gradle/libs.versions.toml
```

AGP 9 requires KMP code in a separate library module (`com.android.kotlin.multiplatform.library`) — the Android app module cannot apply the KMP plugin directly. That's why `:shared` exists.

## Build

**JDK 17 required.** Gradle 8.14 does not yet support JDK 25 (JBR). Point Gradle at JDK 17:

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew :composeApp:assembleDebug
```

In Android Studio: *Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK* → choose a JDK 17 install.

**iOS is stubbed.** Targets are commented out in `composeApp/build.gradle.kts`; sources under `composeApp/src/iosMain/` and `iosApp/` are kept as architectural placeholders only. See [`iosApp/README.md`](iosApp/README.md) to re-enable.

## iOS

The `iosApp/` directory currently contains only `iOSApp.swift`. Generate the Xcode project from Android Studio's KMP plugin (or copy the standard KMP template `iosApp.xcodeproj`). The Swift entrypoint already binds to `MainViewControllerKt.MainViewController()`.

## Next steps

- Add Koin once a second injected dependency appears.
- Add Ktor + a UV API client (e.g. open-meteo) under `commonMain`.
- Add Navigation Compose Multiplatform when a second screen is needed.
- Add SQLDelight or Room when persistence is needed.
