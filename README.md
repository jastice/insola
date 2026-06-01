# insola

Kotlin Multiplatform UV-tracking app. Android first, iOS to follow.

On launch it resolves the device location, fetches today's hourly UV forecast (Open-Meteo), and drives a live dashboard — current UV, burn budget / time-to-burn, vitamin-D, and a sunscreen-decay model — with manual outdoor-session logging. A hidden long-press gesture (debug builds) swaps in synthetic dev scenarios.

## Stack

- Kotlin 2.3.21, Gradle 9.5.1, AGP 9.2.1
- Compose Multiplatform 1.11 (shared UI in `commonMain`)
- AndroidX Lifecycle ViewModel (multiplatform)
- Ktor 3.1 + kotlinx-serialization → Open-Meteo Air-Quality API (HTTPS, keyless)
- Play Services Fused location (Android), with an IP-geo / timezone fallback chain
- JDK 17 toolchain
- DI: manual constructor injection (add Koin when wiring grows)
- Persistence / navigation / offline caching: not wired yet

## Layout

```
shared/             # KMP library: Compose UI + ViewModel + domain/data/location
  src/commonMain/   # UvDay model, dashboard, dose models, Open-Meteo client, location interfaces
  src/androidMain/  # FusedDeviceLocationSource (Play Services)
  src/commonTest/
  src/iosMain/      # iOS entrypoint factory (stubbed)
composeApp/         # Android app: MainActivity (builds HTTP client + providers) + manifest
  src/main/
iosApp/             # Xcode wrapper (stubbed — see iosApp/README.md)
gradle/libs.versions.toml
```

AGP 9 requires KMP code in a separate library module (`com.android.kotlin.multiplatform.library`) — the Android app module cannot apply the KMP plugin directly. That's why `:shared` exists.

## Build

**JDK 17 required.** Gradle 9.5.1 does not support JDK 25 (JBR). Point Gradle at JDK 17:

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew :composeApp:assembleDebug
JAVA_HOME=/path/to/jdk-17 ./gradlew :shared:testAndroidHostTest
```

In Android Studio: *Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK* → choose a JDK 17 install.

A live run needs an emulator/device **with Play Services** (Fused location). Without it, the location chain still falls through to IP-geo / timezone and the app shows an approximate forecast.

**iOS is stubbed.** Targets are commented out in `composeApp/build.gradle.kts`; sources under `composeApp/src/iosMain/` and `iosApp/` are kept as architectural placeholders only. See [`iosApp/README.md`](iosApp/README.md) to re-enable.

## iOS

The `iosApp/` directory currently contains only `iOSApp.swift`. Generate the Xcode project from Android Studio's KMP plugin (or copy the standard KMP template `iosApp.xcodeproj`). The Swift entrypoint already binds to `MainViewControllerKt.MainViewController()`.

## Next steps

- Background location collection (the location layer is structured for it; v1 is foreground-only).
- Offline caching / persistence of the day's forecast (SQLDelight or Room).
- Manual city/coordinate entry and multi-day forecasts.
- Add Koin if manual DI in `MainActivity` grows unwieldy.
- Add Navigation Compose Multiplatform when a second screen is needed.
- iOS: re-enable targets (see [`iosApp/README.md`](iosApp/README.md)) and supply a Core Location `DeviceLocationSource`.

## License

Source-available under [PolyForm Strict 1.0.0](LICENSE). Not open source — see [NOTICE](NOTICE) for distribution and contribution terms.
