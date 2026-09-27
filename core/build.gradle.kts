import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

/**
 * Pure Kotlin: care logic, mood engine, adaptive reminders, sprite generator, PNG encoder,
 * map privacy grid. No platform or UI dependencies, so it is fast to test:
 *   ./gradlew :core:jvmTest
 * and has a desktop tool for the photo likeness test:
 *   ./gradlew :core:spriteLab --args="path/to/photos out"
 */
kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    val desktop = jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    tasks.register<JavaExec>("spriteLab") {
        group = "pawpixel"
        description = "Turns a folder of pet photos into sprites, mood poses and reveal cards."
        val main = desktop.compilations.getByName("main")
        dependsOn(main.compileTaskProvider)
        classpath = files(main.output.allOutputs, main.runtimeDependencyFiles ?: files())
        mainClass.set("com.pawpixel.tools.SpriteLabKt")
        workingDir = rootProject.projectDir
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    // The web Pet Maker (web/) runs this same engine in the browser; see core/src/jsMain.
    js {
        outputModuleName = "PawPixel-core"
        browser { testTask { enabled = false } }
        binaries.library()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "com.pawpixel.core"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
