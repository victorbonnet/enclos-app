// AGP 9 compiles Kotlin itself (built-in Kotlin): no Kotlin Android plugin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "io.github.victorbonnet.enclos"
    // The newest stable SDK platform: platforms;android-37.2
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "io.github.victorbonnet.enclos"
        // Android 9: StrongBox and hardware key attestation, for the device key
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.zxing.android.embedded)
    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }
    debugImplementation(libs.androidx.compose.ui.tooling)
}
