import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// ---- Pet map settings, baked in at build time ----
// From environment variables (CI secrets) or a local, git-ignored `pawpixel.properties` file.
// Blank values build an app whose map screen says "not set up yet". See docs/MAP_SETUP.md.
val mapFile = rootProject.file("pawpixel.properties")
val mapProps = Properties().apply { if (mapFile.exists()) mapFile.inputStream().use { load(it) } }
val mapKeys = listOf(
    "PAWPIXEL_SUPABASE_URL", "PAWPIXEL_SUPABASE_ANON_KEY", "PAWPIXEL_TILE_URL", "PAWPIXEL_TILE_ATTRIBUTION",
    "PAWPIXEL_GOOGLE_WEB_CLIENT_ID", "PAWPIXEL_TEST_EMAIL", "PAWPIXEL_TEST_PASSWORD",
)
val mapValues = mapKeys.associateWith { k ->
    (System.getenv(k) ?: mapProps.getProperty(k).orEmpty()).replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
}
val mapConfigSource = """
    |package com.pawpixel.app
    |
    |import com.pawpixel.map.MapSettings
    |
    |/** Generated at build time from environment variables or pawpixel.properties. Do not edit. */
    |internal val MapBuildConfig = MapSettings(
    |    supabaseUrl = "${mapValues["PAWPIXEL_SUPABASE_URL"]}",
    |    anonKey = "${mapValues["PAWPIXEL_SUPABASE_ANON_KEY"]}",
    |    tileUrl = "${mapValues["PAWPIXEL_TILE_URL"]}",
    |    tileAttribution = "${mapValues["PAWPIXEL_TILE_ATTRIBUTION"]}",
    |    googleWebClientId = "${mapValues["PAWPIXEL_GOOGLE_WEB_CLIENT_ID"]}",
    |    testEmail = "${mapValues["PAWPIXEL_TEST_EMAIL"]}",
    |    testPassword = "${mapValues["PAWPIXEL_TEST_PASSWORD"]}",
    |)
    |""".trimMargin()
val generateMapConfig by tasks.registering {
    val out = layout.buildDirectory.dir("generated/mapConfig/kotlin")
    val source = mapConfigSource
    inputs.property("source", source)
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/pawpixel/app/MapBuildConfig.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(source)
    }
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateMapConfig)
        }
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources) // the app's fonts (Fredoka, Nunito; see docs/fonts)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.glance.appwidget)
            implementation(libs.androidx.glance.material3)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.mlkit.subject.segmentation)
            // Google sign-in for the pet map (Credential Manager)
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.play.services)
            implementation(libs.googleid)
        }
        // End-to-end test on a real emulator: see scripts/android-e2e.sh and the "android-e2e" CI job.
        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.espresso.intents)
            implementation(libs.androidx.uiautomator)
            implementation(libs.junit)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.pawpixel.app.res"
    generateResClass = always
}

android {
    namespace = "com.pawpixel.app"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.pawpixel.app"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        // Play needs a higher versionCode for every upload: CI passes the run number.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // PawPixel speaks English and Filipino: drop the libraries' strings in 80+ other languages.
        @Suppress("DEPRECATION")
        resourceConfigurations += listOf("en", "fil", "tl")
    }
    // Release signing comes from environment variables (CI secrets), never from the repo.
    val keystore = System.getenv("PAWPIXEL_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("PAWPIXEL_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("PAWPIXEL_KEY_ALIAS")
            keyPassword = System.getenv("PAWPIXEL_KEY_PASSWORD")
        }
    }
    buildTypes {
        // Debug builds may talk to a local test server over plain HTTP (the CI end-to-end run).
        getByName("debug") { manifestPlaceholders["cleartext"] = "true" }
        getByName("release") {
            manifestPlaceholders["cleartext"] = "false"
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
