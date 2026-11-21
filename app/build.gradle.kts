plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voiceime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voiceime"
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = 1.2.2

        // Offline voice (Sherpa-ONNX) ships arm64 native libs only: the AAR
        // carries all four ABIs (~110 MB); the user's phone is arm64.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // Speech key is injected at build time from the environment only.
        // It must never be hardcoded, logged, or committed.
        val rawKey = System.getenv("DOUBAO_API_KEY_FROM_CONFIG")?.trim().orEmpty()
        val escapedKey = rawKey.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "DOUBAO_API_KEY", "\"$escapedKey\"")
        // Correction shared secret, same injection pattern as the speech key.
        val rawToken = System.getenv("VOICEIME_CORRECT_TOKEN")?.trim().orEmpty()
        val escapedToken = rawToken.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "CORRECT_TOKEN", "\"$escapedToken\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // tar.bz2 extraction for the offline voice pack download.
    implementation("org.apache.commons:commons-compress:1.26.0")
    // Sherpa-ONNX Android AAR (official prebuilt release asset, no Maven
    // artifact): CI downloads it into app/libs/ before the build.
    implementation(files("libs/sherpa-onnx-2.0.2.aar"))
}
