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
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2-1.0.0")
            implementation(libs.ktor.http)
            implementation(libs.serialization.json)
        }
        androidMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
            api(libs.activity)
            implementation(libs.androidx.webkit)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        androidUnitTest.dependencies {
            implementation(libs.junit)
            implementation("org.robolectric:robolectric:4.16.1")
        }
    }
}

android {
    namespace = "io.github.gycrosskit.composewebview"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    // 独立下载的测试 SDK 使用既有受控镜像，避免 Maven Central 入口限流。
    testOptions.unitTests.all {
        it.systemProperty("robolectric.dependency.repo.url", "https://repo.huaweicloud.com/repository/maven")
        val scripts = rootProject.layout.buildDirectory.dir("early-scripts/${it.name}")
        it.outputs.dir(scripts)
        it.systemProperty("webview.earlyScriptsOutput", scripts.get().asFile.absolutePath)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
