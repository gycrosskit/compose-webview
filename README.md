# Compose WebView Multiplatform

面向 Android 和 iOS 的 Compose Multiplatform WebView 组件，提供共用的请求、状态、导航、事件与安全 API。Android 使用 WebView，iOS 使用 WKWebView。业务 URL、鉴权、JavaScript 处理器和页面 UI 由接入方负责。

## 平台与产物

| 平台 | 产物 | 状态 |
| --- | --- | --- |
| Android | AAR | 支持，最低 API 24 |
| iOS Arm64、Simulator Arm64、x64 | Kotlin/Native KLIB | 支持 KMP 工程接入 |
| HarmonyOS | 无 | 尚未实现 |

iOS 产物是 KMP 模块使用的 KLIB，**不是**独立的 Swift Package 或 XCFramework。KMP 应用可在共享模块引入它，并继续由应用导出自己的 iOS framework。

## 引入依赖

在 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 中加入 JitPack：

```kotlin
maven { url = uri("https://jitpack.io") }
```

然后在 KMP 模块中引入带版本的模块坐标：

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.github.gycrosskit.compose-webview:compose-webview:0.1.1")
        }
    }
}
```

请使用上面的 KMP 模块坐标，不要使用 JitPack 的仓库聚合坐标；依赖版本应固定。

## 构建与验证

本地验证需要 JDK 17 或更新版本、Android SDK 36，以及用于 iOS 构建的 Xcode：

```bash
./gradlew testDebugUnitTest compileKotlinIosSimulatorArm64 publishToMavenLocal
```

工程使用 Kotlin 2.2.21、Compose Multiplatform 1.10.3 和 Ktor 3.3.3。JitPack 根据 Git 标签使用 JDK 17 执行 `publishToMavenLocal`。Android 文件选择器自带 FileProvider，宿主无需为本组件再声明一个。

## 安全边界

JavaScript、文件选择、媒体采集和桥接访问默认关闭；宿主需要主动开启并提供可信来源。桥接事件只暴露经过验证的主 frame 与来源的数据。SSL 错误会被拒绝。

可选的 `AppWebViewLogSink` 会收到完整 URL，包括查询参数和片段。只有当日志存储与共享策略允许这些数据时才安装它；未安装时组件不会写诊断日志。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。
