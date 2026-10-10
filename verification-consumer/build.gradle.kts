plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0"
    kotlin("plugin.compose") version "2.2.21-1.0.0" apply false
    id("com.android.application") version "8.10.1"
}

val componentVersion = providers.gradleProperty("webViewVersion").orElse("0.2.0-rc.12").get()
val renderFrameworkDir = providers.gradleProperty("renderFrameworkDir").orNull
val simRenderFrameworkDir = providers.gradleProperty("simRenderFrameworkDir").orNull
val verifyCmp = providers.gradleProperty("verifyCmp").orElse("false").get().toBoolean()
val verifyNavigation = providers.gradleProperty("verifyNavigation").orElse("false").get().toBoolean()
val verifyPageChannels = providers.gradleProperty("verifyPageChannels").orElse("false").get().toBoolean()
val verifyFullscreenControls = providers.gradleProperty("verifyFullscreenControls").isPresent
val verifyKuiklyCompose = providers.gradleProperty("verifyKuiklyCompose").isPresent
if (verifyCmp || verifyKuiklyCompose) apply(plugin = "org.jetbrains.kotlin.plugin.compose")
kotlin {
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    iosArm64 {
        binaries.framework {
            baseName = "WebViewConsumer"
            renderFrameworkDir?.let { linkerOpts("-F$it", "-framework", "OpenKuiklyIOSRender") }
        }
    }
    iosX64 { binaries.framework { baseName = "WebViewConsumer" } }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "WebViewConsumer"
            simRenderFrameworkDir?.let { linkerOpts("-F$it", "-framework", "OpenKuiklyIOSRender") }
        }
    }
    ohosArm64 { binaries.sharedLib { baseName = "webview_consumer" } }
    sourceSets {
        if (verifyKuiklyCompose) commonMain.get().kotlin.srcDir("src/kuiklyComposeMain/kotlin")
        if (verifyFullscreenControls) {
            check(verifyKuiklyCompose && !verifyCmp) { "Fullscreen controls probe requires KuiklyCompose mode" }
            commonMain.get().kotlin.srcDir("src/fullscreenKuiklyComposeMain/kotlin")
        }

        if (verifyPageChannels) {
            commonMain.get().kotlin.srcDir("src/pageChannelsMain/kotlin")
            if (!verifyCmp) commonMain.get().kotlin.srcDir("src/pageChannelsKuiklyMain/kotlin")
        }
        commonMain.dependencies {
            implementation("com.github.gycrosskit.compose-webview:webview-core:$componentVersion")
            if (!verifyCmp) implementation("com.github.gycrosskit.compose-webview:webview-kuikly:$componentVersion")
        }
        if (!verifyCmp) commonMain.get().kotlin.srcDir("src/kuiklyMain/kotlin")
        val cmpMain = if (verifyCmp) create("cmpMain") {
            dependsOn(commonMain.get())
            if (verifyNavigation) kotlin.srcDir("src/navigationMain/kotlin")
            if (verifyPageChannels) kotlin.srcDir("src/pageChannelsCmpMain/kotlin")
            dependencies {
                implementation("com.github.gycrosskit.compose-webview:compose-webview:$componentVersion")
                implementation("org.jetbrains.compose.runtime:runtime:1.10.3")
                implementation("org.jetbrains.compose.ui:ui:1.10.3")
            }
        } else null
        androidMain {
            if (!verifyCmp) kotlin.srcDir("src/kuiklyAndroidMain/kotlin")
            if (verifyFullscreenControls) kotlin.srcDir("src/fullscreenAndroidMain/kotlin")
            if (verifyKuiklyCompose) kotlin.srcDir("src/kuiklyComposeAndroidMain/kotlin")
            cmpMain?.let { dependsOn(it) }
        }
        val iosMain by creating { dependsOn(cmpMain ?: commonMain.get()) }
        iosArm64Main { dependsOn(iosMain) }
        iosX64Main { dependsOn(iosMain) }
        iosSimulatorArm64Main { dependsOn(iosMain) }
    }
}

tasks.register("verifyNoCmpUi") {
    doLast {
        check(!verifyCmp) { "Run this check in Kuikly mode" }
        val deps = configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
        val forbidden = deps.filter {
            it.group.startsWith("org.jetbrains.compose.ui") || it.group.startsWith("org.jetbrains.compose.foundation") ||
                it.group.startsWith("org.jetbrains.compose.material") || it.group.startsWith("androidx.compose.ui") ||
                it.group.startsWith("androidx.compose.foundation") || it.group.startsWith("androidx.compose.material")
        }
        check(forbidden.isEmpty()) { "Kuikly consumer pulls a second CMP UI: $forbidden" }
        if (verifyKuiklyCompose) check(deps.any { it.group == "com.tencent.kuikly-open" && it.name.startsWith("compose") })
        println("PASS no second CMP UI (KuiklyCompose presence required when API probe enabled); Compose runtime: " + deps.filter { it.group.contains("compose.runtime") })
    }
}

tasks.register("verifyExactComponentVersion") {
    doLast {
        val versions = configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }.filter { it.group == "com.github.gycrosskit.compose-webview" }
        check(versions.isNotEmpty() && versions.all { it.version == componentVersion })
        println("Exact WebView artifacts: " + versions.joinToString())
    }
}

android {
    namespace = "io.github.gycrosskit.webview.consumer"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.gycrosskit.webview.consumer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}

tasks.register("verifyNoKuikly") {
    doLast {
        check(verifyCmp) { "Run this check in CMP mode" }
        val components = configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult.allComponents
        check(components.none {
            it.moduleVersion?.group == "com.tencent.kuikly-open" || it.moduleVersion?.name.orEmpty().endsWith("-kuikly-android")
        }) { "CMP-only consumer unexpectedly pulls Kuikly runtime" }
        println("PASS CMP-only runtime has no Kuikly")
    }
}
