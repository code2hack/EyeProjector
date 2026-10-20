plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.code2hack.eyeprojector.protocol"
    compileSdk = 35
    defaultConfig {
        minSdk = 32
        testInstrumentationRunner = "com.code2hack.eyeprojector.protocol.PairingInstrumentation"
    }
    packaging.resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.bouncycastle:bcpkix-jdk18on:1.83")
    testImplementation("junit:junit:4.13.2")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
