plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.code2hack.eyeprojector.directrg"
    compileSdk = 35
    packaging.resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    defaultConfig {
        applicationId = "com.code2hack.eyeprojector.directrg"
        minSdk = 32
        targetSdk = 35
        versionCode = 1
        versionName = "bootstrap"
        testApplicationId = "com.code2hack.eyeprojector.directrg.test"
        testInstrumentationRunner = "com.code2hack.eyeprojector.directrg.ProjectionInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":protocol"))
    testImplementation("junit:junit:4.13.2")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
