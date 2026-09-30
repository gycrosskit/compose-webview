# GY CrossKit WebView

Android、iOS、HarmonyOS 的系统网页组件封装。CMP 和 Kuikly 使用同一套请求、事件与安全契约；网页 URL、鉴权、业务 JSBridge 处理器和页面导航由宿主提供。

本轮预发布版本为 **`0.2.0-rc.1`**，Maven / 原生 Pod / HAR 使用同一版本。设备验收尚未完成，生产接入前仍需真实 H5 和平台行为回归。已发布的 CMP `0.1.1` 坐标继续可用。

## 目录与接入边界

| 目录 | 职责 |
| --- | --- |
| `src/`（根 `compose-webview` 产物） | 现有 Android/iOS CMP 入口与状态 |
| `webview-core/` | UI 无关的请求、事件、安全策略、wire 协议及 Android 底层能力 |
| `webview-kuikly/` | Kuikly 原生 View DSL 与 Android 适配，不依赖 CMP UI/runtime |
| `ios/` | iOS Kuikly 的 WKWebView 原生接线 |
| `ohos/` | 自己封装 ArkWeb 的 HAR，不依赖社区 WebView 包 |
| `verification-consumer/` | 只通过 Maven 坐标消费组件的 Android/iOS/OHOS 验证工程 |

底层继续使用 Android WebView、iOS WKWebView 和鸿蒙 ArkWeb。KMP 产物是 AAR/KLIB；iOS 的 Kuikly 原生接线和鸿蒙 HAR 需要分别安装，不能用一个平台的编译结果代替另一个平台验收。

## Kotlin 接入

CMP 使用根模块；Kuikly 使用独立模块，按宿主 UI 引擎选择：

```kotlin
// 预发布固定版本；settings 中添加 https://jitpack.io。
val webViewVersion = "0.2.0-rc.1"
commonMain.dependencies {
    implementation("com.github.gycrosskit.compose-webview:webview-kuikly:$webViewVersion")
}
// 现有 CMP 页面则使用 compose-webview:$webViewVersion。
```

Kuikly 原生 View 需要显式尺寸：

```kotlin
GYWebView {
    attr {
        size(320f, 480f)
        request(WebViewRequest(WebViewContent.Url("https://example.com/")))
    }
    event { onEvent { event -> /* 交给宿主 */ } }
}
```

通过 `ViewRef<GYWebView>` 调用 `goBack`、`goForward`、`reload`、`stopLoading`、`evaluateJavascript` 和 `exitFullscreen`。Kuikly 的返回值异步交付；`goBack` 回报是否消费返回，宿主据此决定是否退出页面。原生 View 名统一为 `GYWebView`，必须先完成对应平台注册。

Android 宿主在 `registerExternalRenderView` 中调用 `registerGYWebView()`；文件和媒体能力要求宿主提供 `ComponentActivity`。iOS 在 Podfile 中使用本地 `pod 'GYWebView', :path => '<本仓库路径>'`，链接 `OpenKuiklyIOSRender 2.28.0` 和 `-ObjC`，由 Kuikly 按同名 Objective-C 类发现组件。鸿蒙安装实际 HAR 并注册 Creator，见 [鸿蒙接入](ohos/webview-native/README.md)。

## 导航与 JSBridge

Kuikly 原生回调无法同步等待 Kotlin 的业务决定。拦截规则通过 `request.navigationPolicy` 预先下发，原生在首个请求前判断；`WebViewEvent.Navigation` 用于通知宿主已允许或已阻止的导航。外部跳转和业务路由仍由宿主执行。

`allowedOrigins` 只限制主帧，与 JavaScript/Bridge 开关独立；精确比较 scheme、host 和有效端口（HTTP 80、HTTPS 443），路径与查询不参与来源匹配。空集合不限制来源。可与 `blockedRules` 组合，让只读协议保持同源，同时把商城链接交给宿主路由：

```kotlin
navigationPolicy = WebViewNavigationPolicy(
    allowedOrigins = setOf("https://example.com"),
    blockedRules = listOf(WebViewUrlRule.Contains("/mall/")),
)
```

规则在三端原生同步执行，`Navigation(blocked = true)` 报告已拒绝的导航。HTML 仅保留当前加载的一次内部首航，后续 data 导航拒绝。以上策略可以在 `javaScriptEnabled = false`、Bridge 关闭时使用。

JavaScript、Bridge、文件选择和媒体采集需要显式开启；高权限能力需要可信 HTTPS 来源。低权限 `pageBridgeEnabled` 仅限初始精确同源页面，不能代替高权限来源授权。证书错误拒绝继续加载。

iOS 最低为 15.0，但原生文件选择代理仅在 18.4 及以上可用。旧系统拒绝开启 `fileChooserEnabled` 的请求；关闭时的 DOM 交互拦截不能作为旧系统的原生安全隔离保证。需要严格禁止网页文件访问的宿主应限制系统版本或网页内容。

鸿蒙文件选择使用系统 DocumentViewPicker；H5 `capture` 拍摄未实现。可信且就绪的当前 HTTPS 页面触发 capture 时，原生以空文件列表完成一次系统结果，并发送 `WebViewEvent.CapabilityUnsupported(WebViewCapability.FILE_CAPTURE)`；这表示不支持，不表示成功、用户取消或权限拒绝，也不会请求拍摄权限。普通选择器在导航、隐藏、请求切换和销毁时撤销，迟到文件 URI 丢弃。SDK 未提供真实来源 frame，详见 [HAR 能力限制](ohos/webview-native/README.md)。

H5 通用入口为 `GYWebViewBridge.postMessage(handlerName, data)`，宿主收到 `WebViewEvent.BridgeMessage` 后自行解释业务命令。历史同义入口继续兼容；组件不提供登录 Token、账号或自动执行任意业务命令。鸿蒙通过原生投递到可信主文档的专用 MessagePort 通信，导航、输入变更、隐藏和销毁后撤销旧通道。

业务脚本用 `WebViewScript` 描述，默认仅在可信主文档执行；外部输入必须正确编码，不能拼入 JavaScript 源码。

## 迁移与验证

原有 CMP 入口、package 和主要函数保留；公共模型移到 `webview-core`，由根模块传递依赖。新增导航/历史和 `CapabilityUnsupported` 事件后，使用穷尽 `when` 的宿主需覆盖新事件。升级 Kotlin/OHOS 工具链、原生注册和能力差异参见各平台文档与 [验证记录](VALIDATION.md)。

工具链：Kotlin `2.2.21-1.0.0`、Kuikly `2.28.0-2.0.21-ohos`、CMP `1.10.3`。构建和设备验收分开记录；不要仅凭 KLIB 或 HAR 编译成功就删除应用中的旧组件接线。

AndroidX Activity 会传递稳定性注解包，该包不含 Compose UI 或执行运行时；独立消费者的门禁仅放行这两个注解模块。其他 Compose UI/runtime 依赖仍会使 Kuikly 验证失败。

本地 Maven 打包执行 `bash scripts/package-maven.sh`。JitPack 使用 macOS 预构建归档和 SHA-256 校验；`release-checksums.txt` 保存不可变标签的真实归档校验值。远程消费者使用 `-PremoteOnly -PwebViewVersion=0.2.0-rc.1`，该模式仅从 JitPack 读取本组件。HAR 的 ohpm 审核状态须查询 registry 并实际安装确认；同标签 GitHub Release 的 HAR 可用于审核期间的远程预发布验收。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。平台系统框架和 Kuikly 依赖分别遵循原厂许可。
