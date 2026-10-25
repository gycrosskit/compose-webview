plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0"
    kotlin("plugin.compose") version "2.2.21-1.0.0" apply false
    id("com.android.application") version "8.10.1"
}

val componentVersion = providers.gradleProperty("webViewVersion").orElse("1.0.0").get()
val renderFrameworkDir = providers.gradleProperty("renderFrameworkDir").orNull
val simRenderFrameworkDir = providers.gradleProperty("simRenderFrameworkDir").orNull
val verifyPageChannels = providers.gradleProperty("verifyPageChannels").orElse("false").get().toBoolean()
val verifyFullscreenControls = providers.gradleProperty("verifyFullscreenControls").isPresent
val verifyKuiklyCompose = providers.gradleProperty("verifyKuiklyCompose").isPresent
val verifyLocalSource = providers.gradleProperty("verifyLocalSource").isPresent
check(!verifyLocalSource || !providers.gradleProperty("remoteOnly").isPresent) { "Local source and remote-only validation are separate modes" }
if (verifyKuiklyCompose) apply(plugin = "org.jetbrains.kotlin.plugin.compose")
kotlin {
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    iosArm64 {
        binaries.framework {
            baseName = "WebViewConsumer"
            renderFrameworkDir?.let { linkerOpts("-F$it", "-framework", "OpenKuiklyIOSRender") }
        }
    }
    iosX64 {
        binaries.framework {
            baseName = "WebViewConsumer"
            simRenderFrameworkDir?.let { linkerOpts("-F$it", "-framework", "OpenKuiklyIOSRender") }
        }
    }
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
            check(verifyKuiklyCompose) { "Fullscreen controls probe requires KuiklyCompose mode" }
            commonMain.get().kotlin.srcDir("src/fullscreenKuiklyComposeMain/kotlin")
        }

        if (verifyPageChannels) {
            commonMain.get().kotlin.srcDir("src/pageChannelsMain/kotlin")
            commonMain.get().kotlin.srcDir("src/pageChannelsKuiklyMain/kotlin")
        }
        commonMain.dependencies {
            implementation("com.github.gycrosskit.compose-webview:webview-core:$componentVersion")
            implementation("com.github.gycrosskit.compose-webview:webview-kuikly:$componentVersion")
        }
        commonMain.get().kotlin.srcDir("src/kuiklyMain/kotlin")
        androidMain {
            kotlin.srcDir("src/kuiklyAndroidMain/kotlin")
            if (verifyFullscreenControls) kotlin.srcDir("src/fullscreenAndroidMain/kotlin")
            if (verifyKuiklyCompose) kotlin.srcDir("src/kuiklyComposeAndroidMain/kotlin")
        }
        val iosMain by creating { dependsOn(commonMain.get()) }
        iosArm64Main { dependsOn(iosMain) }
        iosX64Main { dependsOn(iosMain) }
        iosSimulatorArm64Main { dependsOn(iosMain) }
    }
}

tasks.register("verifyNoCmpUi") {
    doLast {
        val resolution = configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult
        val unresolved = resolution.allDependencies.filterIsInstance<org.gradle.api.artifacts.result.UnresolvedDependencyResult>()
        check(unresolved.isEmpty()) { "Unresolved runtime dependencies: ${unresolved.map { it.attempted }}" }
        val deps = resolution.allComponents
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
        val resolution = configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult
        val unresolved = resolution.allDependencies.filterIsInstance<org.gradle.api.artifacts.result.UnresolvedDependencyResult>()
        check(unresolved.isEmpty()) { "Unresolved runtime dependencies: ${unresolved.map { it.attempted }}" }
        val versions = resolution.allComponents
            .mapNotNull { it.moduleVersion }.filter { it.group == "com.github.gycrosskit.compose-webview" }
        check(versions.isNotEmpty() && versions.all { it.version == componentVersion })
        if (verifyLocalSource) {
            val projects = resolution.allComponents.filter { it.moduleVersion?.group == "com.github.gycrosskit.compose-webview" }
            check(projects.size == 2 && projects.all { it.id is org.gradle.api.artifacts.component.ProjectComponentIdentifier })
            check(projects.map { (it.id as org.gradle.api.artifacts.component.ProjectComponentIdentifier).projectPath }.toSet() == setOf(":webview-core", ":webview-kuikly"))
            println("Local source substitution only; this is not remote artifact proof")
        } else {
            check(versions.map { it.name }.toSet() == setOf("webview-core", "webview-core-android", "webview-kuikly", "webview-kuikly-android")) { "Published validation requires exactly the two WebView products: $versions" }
        }
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
