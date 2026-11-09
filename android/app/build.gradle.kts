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
        versionCode = 2
        versionName = "0.1.1"
    }

    // Debug builds, on a laptop and in CI, are signed with the project's one
    // debug key, kept out of git (docs/adr/0004), so each installs over the
    // last: the keystore at ENCLOS_DEBUG_KEYSTORE, else
    // ~/.android/enclos-debug.keystore. Without either, the machine's own
    // debug key, which no other build shares.
    signingConfigs {
        getByName("debug") {
            val named = System.getenv("ENCLOS_DEBUG_KEYSTORE")
            val shared = file(named ?: "${System.getProperty("user.home")}/.android/enclos-debug.keystore")
            if (shared.isFile) {
                storeFile = shared
                // Android's debug keystore defaults: the file is what is kept out of git.
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            } else if (named != null) {
                throw GradleException("ENCLOS_DEBUG_KEYSTORE is set, but $shared is not a file")
            }
        }
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
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.zxing.android.embedded)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
