plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    `maven-publish`
}

group = providers.environmentVariable("GROUP").orElse("io.github.gycrosskit").get()
version = providers.environmentVariable("VERSION").orElse("0.1.0-SNAPSHOT").get()

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.ui)
            implementation(libs.cmp.foundation)
            implementation(libs.ktor.http)
        }
        androidMain.dependencies {
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.activity)
            implementation(libs.androidx.webkit)
        }
        iosMain.dependencies {
            implementation(libs.lifecycle.runtime.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidUnitTest.dependencies {
            implementation(libs.junit)
        }
    }
}

android {
    namespace = "io.github.gycrosskit.composewebview"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
