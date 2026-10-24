import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.code2hack.eyeprojector.directhost"
    compileSdk = 35
    ndkVersion = "30.0.16248370"
    packaging.resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    defaultConfig {
        applicationId = "com.code2hack.eyeprojector.directhost"
        minSdk = 32
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        targetSdk = 35
        versionCode = 1
        versionName = "bootstrap"
        testApplicationId = "com.code2hack.eyeprojector.directhost.test"
        testInstrumentationRunner = "com.code2hack.eyeprojector.directhost.ChosenAppsInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    buildFeatures { aidl = true }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/bootstrapAssets"))
}

val prepareBootstrapAssets by tasks.registering {
    val server = rootProject.layout.projectDirectory.file("third_party/scrcpy-server-v5.0.1")
    inputs.file(server)
    val notices = listOf("scrcpy-LICENSE.txt", "Shizuku-LICENSE.txt", "NOTICE.md")
        .map { rootProject.layout.projectDirectory.file("third_party/$it") }
    inputs.files(notices)
    val destination = layout.buildDirectory.dir("generated/bootstrapAssets")
    outputs.dir(destination)
    doLast {
        val bytes = server.asFile.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(digest == "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536") {
            "Run sh tools/fetch-scrcpy.sh to provision the pinned server"
        }
        destination.get().asFile.apply { mkdirs() }.resolve("scrcpy-server-v5.0.1").writeBytes(bytes)
        notices.forEach { it.asFile.copyTo(destination.get().file(it.asFile.name).asFile, overwrite = true) }
    }
}
tasks.named("preBuild").configure { dependsOn(prepareBootstrapAssets) }
dependencies {
    implementation(project(":protocol"))
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
