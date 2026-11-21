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
        versionCode = 1
        versionName = "0.1.0"

        // Speech key is injected at build time from the environment only.
        // It must never be hardcoded, logged, or committed.
        val rawKey = System.getenv("DOUBAO_API_KEY_FROM_CONFIG")?.trim().orEmpty()
        val escapedKey = rawKey.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "DOUBAO_API_KEY", "\"$escapedKey\"")
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
}
