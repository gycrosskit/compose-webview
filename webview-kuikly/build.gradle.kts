plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    `maven-publish`
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    ohosArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":webview-core"))
            api(libs.kuikly.core)
        }
        androidMain.dependencies {
            api(libs.kuikly.render.android)
            implementation(libs.androidx.webkit)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        androidUnitTest.dependencies { implementation(libs.junit) }
    }
}

android {
    namespace = "io.github.gycrosskit.composewebview.kuikly"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
