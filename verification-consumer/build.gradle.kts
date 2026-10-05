plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0"
    kotlin("plugin.compose") version "2.2.21-1.0.0" apply false
    id("com.android.application") version "8.10.1"
}

val componentVersion = providers.gradleProperty("webViewVersion").orElse("0.2.0-rc.9").get()
val renderFrameworkDir = providers.gradleProperty("renderFrameworkDir").orNull
val simRenderFrameworkDir = providers.gradleProperty("simRenderFrameworkDir").orNull
val verifyCmp = providers.gradleProperty("verifyCmp").orElse("false").get().toBoolean()
if (verifyCmp) apply(plugin = "org.jetbrains.kotlin.plugin.compose")
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
        commonMain.dependencies {
            implementation("com.github.gycrosskit.compose-webview:webview-core:$componentVersion")
            if (!verifyCmp) implementation("com.github.gycrosskit.compose-webview:webview-kuikly:$componentVersion")
        }
        if (!verifyCmp) commonMain.get().kotlin.srcDir("src/kuiklyMain/kotlin")
        val cmpMain = if (verifyCmp) create("cmpMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation("com.github.gycrosskit.compose-webview:compose-webview:$componentVersion")
                implementation("org.jetbrains.compose.runtime:runtime:1.10.3")
                implementation("org.jetbrains.compose.ui:ui:1.10.3")
            }
        } else null
        androidMain { cmpMain?.let { dependsOn(it) } }
        val iosMain by creating { dependsOn(cmpMain ?: commonMain.get()) }
        iosArm64Main { dependsOn(iosMain) }
        iosX64Main { dependsOn(iosMain) }
        iosSimulatorArm64Main { dependsOn(iosMain) }
    }
}

tasks.register("verifyNoCompose") {
    doLast {
        check(!verifyCmp) { "Run this check without -PverifyCmp=true" }
        val components = configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult.allComponents
        check(components.none {
            val group = it.moduleVersion?.group.orEmpty()
            val name = it.moduleVersion?.name.orEmpty()
            // AndroidX Activity 的稳定性注解不包含 Compose UI 或执行运行时。
            val annotationOnly = group == "androidx.compose.runtime" && name in setOf("runtime-annotation", "runtime-annotation-android")
            group.startsWith("org.jetbrains.compose") || (group.startsWith("androidx.compose") && !annotationOnly)
        }) { "Kuikly-only consumer unexpectedly pulls Compose UI/runtime" }
        println("Kuikly-only runtime has no Compose UI/runtime (AndroidX annotations allowed)")
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
        val artifacts = configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        check(artifacts.none { it.moduleVersion.id.group == "com.tencent.kuikly-open" || it.moduleVersion.id.name.endsWith("-kuikly-android") }) { "CMP-only consumer unexpectedly pulls Kuikly runtime" }
    }
}
