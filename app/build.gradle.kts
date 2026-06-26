// ══════════════════════════════════════════════════════════════════════════
//  CalvoNote Mobile — app/build.gradle.kts
//  DevLab · 2026
// ══════════════════════════════════════════════════════════════════════════

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace   = "com.devlab.calvonote"
    compileSdk  = 34

    defaultConfig {
        applicationId   = "com.devlab.calvonote"
        minSdk          = 26
        targetSdk       = 34
        versionCode     = 1
        versionName     = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Nécessaire pour inclure le modèle Vosk dans les assets
    aaptOptions {
        noCompress += listOf("so", "tflite")
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Vosk Android SDK — moteur de reconnaissance vocale offline
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Coroutines Kotlin — gestion asynchrone (chargement modèle + micro)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // UI Android standard
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
