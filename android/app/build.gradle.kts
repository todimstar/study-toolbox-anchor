plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.studytoolbox.anchor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.studytoolbox.anchor"
        minSdk = 29  // Android 10
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-anchor"
        buildConfigField("String", "TOOLBOX_URL", "\"https://toolbox.zakuku.top/\"")
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
    implementation("androidx.core:core-ktx:1.13.1")
}
