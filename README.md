# Compose WebView Multiplatform

An Android and iOS WebView component for Compose Multiplatform. It provides a common request, state, navigation, event, and security API. The Android implementation uses WebView; the iOS implementation uses WKWebView. Application specific URLs, authentication, JavaScript handlers, and UI stay in the host app.

## Platforms

| Target | Artifact | Status |
| --- | --- | --- |
| Android | AAR | Supported, minSdk 24 |
| iOS Arm64, Simulator Arm64, x64 | Kotlin/Native KLIB | Supported for Kotlin Multiplatform consumers |
| HarmonyOS | — | Not implemented |

The iOS artifact is a KLIB for a Kotlin Multiplatform module. It is **not** a Swift Package or a standalone XCFramework. A KMP application can include this dependency in its shared module and continue to export its own iOS framework.

## Dependency

Add JitPack to `dependencyResolutionManagement.repositories` in `settings.gradle.kts`:

```kotlin
maven { url = uri("https://jitpack.io") }
```

Then add the tagged version to the KMP module:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.github.gycrosskit:compose-webview:0.1.0")
        }
    }
}
```

The first public version is available after its JitPack build succeeds. Pin a release tag rather than a moving branch.

## Build

Use JDK 17 or newer, Android SDK 36, and Xcode for local iOS validation:

```bash
./gradlew testDebugUnitTest compileKotlinIosSimulatorArm64 publishToMavenLocal
```

The build uses Kotlin 2.2.21, Compose Multiplatform 1.10.3, and Ktor 3.3.3. On JitPack, `GROUP` and `VERSION` select the Maven coordinates from the repository and Git tag. The Android file chooser has its own FileProvider; the host app does not need to declare one for this library.

## Security

JavaScript, file selection, media capture, and bridge access are off by default. A host must opt in and provide trusted origins. Bridge events expose only data from a verified main frame and origin. SSL errors are rejected.

The optional `AppWebViewLogSink` receives full URLs, including query values and fragments. Treat these messages as sensitive and install a sink only when its storage and sharing policy permits that data. Without a host sink, the library does not write diagnostics.

## License

Apache-2.0. See [LICENSE](LICENSE).
