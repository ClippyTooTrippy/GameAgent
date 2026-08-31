plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.joel.gameagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.joel.gameagent"
        // Gemini Nano / AICore requires API 26+, but the Accessibility
        // gesture APIs we use need 24+. Screenshot capture wants 30+.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // On-device OCR - this is what lets the agent read text off ANY
    // screen (games, video, canvas-rendered UI), not just native Android
    // buttons. Stable release, not the beta genai library that broke the
    // build earlier.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Local persistence for the "memory" the agent learns from
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Gemini Nano decision-making itself is accessed through GeminiNanoBrain.kt
    // via AICore, not a Gradle dependency here - see the TODO in that file.
    testImplementation("junit:junit:4.13.2")
}
