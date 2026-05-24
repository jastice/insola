# iOS — architectural stub

iOS is **not currently built**. The files in this directory and in
`../shared/src/iosMain/` exist only to preserve the KMP module layout so
iOS can be re-enabled later without restructuring.

## Current state

- `iosApp/iosApp/iOSApp.swift` — SwiftUI entrypoint that wraps
  `MainViewControllerKt.MainViewController()` from the Kotlin shared module.
  Not compiled (no Xcode project yet).
- `../shared/src/iosMain/kotlin/com/insola/uv/MainViewController.kt` —
  Kotlin-side factory exposing the shared `App()` composable to UIKit.
  Currently dead code: the `iosArm64()` / `iosSimulatorArm64()` Kotlin targets
  are commented out in `../shared/build.gradle.kts`, so this source set
  is not compiled.

## Why stubbed

- Avoids the Kotlin/Native toolchain download on every Android build.
- Avoids the hard dependency on full Xcode.app (Command Line Tools are not
  sufficient for `linkDebugFrameworkIos*` tasks).
- Keeps the door open: re-enabling is a four-line uncomment, no refactor.

## To enable iOS later

1. Install Xcode from the App Store, then:
   ```bash
   sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
   ```
2. Uncomment the iOS target block in
   `../shared/build.gradle.kts`.
3. Generate an Xcode project for `iosApp/` (easiest: use Android Studio's
   KMP plugin "New iOS App" action, or copy `iosApp.xcodeproj` from a fresh
   `kmp.jetbrains.com` template and point its framework search path at
   `shared/build/xcode-frameworks`).
4. Verify with:
   ```bash
   ./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
   ```

## Do not

- Don't add `iosMain`-only dependencies until the targets are re-enabled —
  Gradle will silently ignore them.
- Don't add `iosX64()` (Intel simulator). Compose Multiplatform 1.11 no
  longer publishes iosX64 artifacts; only `iosArm64` (device) and
  `iosSimulatorArm64` (Apple Silicon simulator) resolve.
