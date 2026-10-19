plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.code2hack.eyeprojector.hostprobe"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.code2hack.eyeprojector.hostprobe"
        minSdk = 32
        targetSdk = 35
        versionCode = 1
        versionName = "bootstrap"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
