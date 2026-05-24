plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "com.insola.uv.shared"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
        withHostTest { }
    }

    // iOS targets are stubbed out — see iosApp/README.md.
    // To re-enable, uncomment below and ensure full Xcode is installed.
    // listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
    //     iosTarget.binaries.framework {
    //         baseName = "Shared"
    //         isStatic = true
    //     }
    // }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
