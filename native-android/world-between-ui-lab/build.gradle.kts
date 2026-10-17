plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.jlz.worldbetween.uilab"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.jlz.worldbetween.uilab"
        minSdk = 26
        targetSdk = 35
        versionCode = 2026092901
        versionName = "0.2.0-v2-ui-preview"
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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
