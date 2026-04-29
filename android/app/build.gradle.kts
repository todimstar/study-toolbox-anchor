plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.silentinstaller"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.silentinstaller"
        minSdk = 29  // Android 10
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Server base URL – your PC's LAN IP (change for different networks)
        buildConfigField("String", "SERVER_BASE_URL", "\"http://172.20.88.75:8000\"")
        buildConfigField("String", "WS_URL", "\"ws://172.20.88.75:8000/ws/device\"")
        // Device pre-shared key – must match DEVICE_PSK on server
        buildConfigField("String", "DEVICE_PSK", "\"device-preshared-key-2024\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // AndroidX core libraries
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    // WorkManager for task scheduling & retry
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // OkHttp for WebSocket & file download
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // JSON parsing (without codegen for simplicity)
    implementation("com.google.code.gson:gson:2.11.0")
}
