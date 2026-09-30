# GY WebView 鸿蒙 HAR

`@gycrosskit/webview` 的 **0.2.0-rc.1 预发布版**。本组件自己封装系统 ArkWeb，供 Kuikly 2.28.0 使用，最低 HarmonyOS 6.0.2 / API 22。依赖只有 `@kuikly-open/render:2.28.0`，不依赖 `@yuki8273/webview-ohos`。

## 安装与注册

HAR 使用同版本的不可变 GitHub Release；ohpm 上架前保持审核状态说明，不能把 prepublish 当作已发布。

```json
{
  "dependencies": {
    "@gycrosskit/webview": "file:./libs/WebViewNative.har"
  }
}
```

宿主把 `GYWebView.VIEW_NAME` 注册进自己的 `KuiklyRenderBaseDelegate`；从本 HAR 导出所需 Kuikly 类型，避免消费工程依赖包内部的目录布局。

```ts
import { GYWebView, KuiklyRenderBaseView, KRRenderViewExportCreator } from '@gycrosskit/webview';

getCustomRenderViewCreatorRegisterMap(): Map<string, KRRenderViewExportCreator> {
  const creators = new Map<string, KRRenderViewExportCreator>();
  creators.set(GYWebView.VIEW_NAME, () => new GYWebView());
  return creators;
}
```

宿主声明 `ohos.permission.INTERNET`。开启 `mediaCaptureEnabled` 时，宿主另外声明 `ohos.permission.CAMERA` / `ohos.permission.MICROPHONE` 及用途；HAR 不替应用写权限说明、账号、支付或业务 Bridge。

## wire 与安全

原生 View 名为 `GYWebView`。`request` 接收公共 `WebViewRequest` JSON 字符串，另有 `visible: Boolean` 和 `onEvent`。请求包含 `content`、`settings`、`security`、`scripts`、`blockedResourceRules`、`navigationPolicy`，与 Kotlin adapter 一致。每个新 request 创建独立 WebviewController / Web 节点；旧节点事件通过实例 token 拒绝，旧异步操作通过 generation 撤销。

原生在加载初始 URL、HTML baseUrl / historyUrl、导航与子资源时同步执行策略。只加载 HTTP / HTTPS，拒绝 URL 用户名密码、file/content/resource、自定义 scheme 和页面 data 导航。HTML 等待 controller attached 后调用 `loadData`；只允许当前 loadData 的一次无用户手势内部 `data:text/html` 主文档导航，保留 baseUrl、encoding、mimeType、historyUrl。导航事件仅用于报告同步判定结果。`navigationPolicy.allowedOrigins` 为可选 HTTP(S) URL 数组，空数组或省略时不限制主帧来源；非空时精确比较 scheme、host 和有效端口，与 JavaScript/Bridge 开关独立，并与主帧 `blockedRules` 组合。

JavaScript、Bridge、文件 URL、媒体采集默认关闭。TLS 错误调用 `handleCancel()`，网络/HTTP 错误只把主文档失败作为整页错误。TLS 回调没有主框架字段，TLS 拒绝仍会上报安全错误。HTML 无可验证 HTTP 来源时，不能获得 Bridge、文件或媒体授权。

| 能力 | 实际实现与限制 |
|---|---|
| URL / HTML | 首航 headers 仅随顶层 `loadUrl` 传入；相对资源使用 HTML baseUrl |
| history / 方法 | 首航 loadUrl/loadData 抛错后 reload 重建 Controller/Web 节点，新 attached 原样重交 URL headers 或 HTML base/history 一次；已提交文档 reload 继续 refresh 保留历史；goBack 优先退出全屏；goForward、stopLoading、exitFullscreen |
| evaluateJavascript | 必须 JS 开启、文档就绪且当前 HTTPS 来源可信，或处于低权限 pageBridge 初始同源；输入 `{script}`，输出 `{result: string|null}` |
| scripts | document-start 原生注入；DOM_READY 等待 DOMContentLoaded；DOCUMENT_FINISHED 在 page-end。脚本只作用于 top；可信门禁默认开启 |
| Bridge | 主文档独占 MessagePort；appBridge 只允许可信 HTTPS；pageBridge 只允许初始精确 HTTP(S) 同源 |
| 媒体采集 | CAMERA/MICROPHONE，可信来源与当前主文档精确同源；系统授权后再次校验当前 request 与 generation。其他资源拒绝 |
| 文件选择 | 可信当前主文档的系统 DocumentViewPicker，单选或最多 10 项多选；不支持 capture，可信且就绪的当前 HTTPS 页面触发时发出 `capabilityUnsupported` / `FILE_CAPTURE`，并以空文件列表完成一次系统结果。系统事件不提供来源 frame；accept 由页面/服务端校验 |
| 全屏 | 单个 owner，保存并恢复原窗口 layoutFullScreen 与方向（包括 UNSPECIFIED）；尺寸为横屏视频时使用 AUTO_ROTATION_LANDSCAPE。宿主使用 fullscreenChanged 调整 Kuikly 页面布局 |
| 可见性 / 释放 | 隐藏、导航、请求切换、render 退出及 onDestroy 撤销消息端口、系统请求和迟到回调；隐藏停止媒体、onInactive，显示后 onActive 并重新握手 |

`capabilityUnsupported` 对应 Kotlin `WebViewEvent.CapabilityUnsupported(WebViewCapability.FILE_CAPTURE)`，只表示平台未实现拍摄，不伪装为成功、用户取消、失败或权限拒绝，也不请求拍摄权限。DocumentViewPicker 结果在导航、隐藏、request 切换和销毁时以空列表结算，迟到回调不能给新文档交付 URI。当前 SDK 缺少真实来源 frame 和独立 user-gesture 字段，不能把当前可信页面检查写成来源 frame 证明；拍摄入口需未来实现并获得真实设备文件 URI 后才能宣布可用。

支持 DOM Storage、图像访问、zoomAccess、混合内容和 cacheMode、User-Agent suffix。Android 专属缩放按钮、viewport/overview、字体缩放和算法变暗设置不在 HAR 实现范围。内核开启新窗口事件分流并在 onWindowNew 同步取消，避免把 target=_blank 降成当前页而绕过 policy。公开的新窗口/自动开窗开启请求、file/content 本地 URL、每实例第三方 Cookie 开启请求明确拒绝；全局 Cookie 默认由 ArkWeb/宿主管理，HAR 不改其他实例的全局开关。没有实现的设置不作为能力承诺。

所有方法回调都为 JSON 对象 `{result: ...}`，不返回裸布尔字符串。`reload/goBack/goForward/stopLoading/exitFullscreen` 的 params 为 null；`evaluateJavascript` 的 params 为 JSON 字符串。historyChanged 通过 `onEvent` 报告 canGoBack / canGoForward。异步调用被撤销后不再投递旧结果。

## H5 Bridge

```js
GYWebViewBridge.postMessage('handlerName', { value: 'data' });
```

载荷为 `handlerName` + 原始 `data` 字符串，组件不解释命令。保留 `JSAndroidBridge.handleJSBridgeMessage` / `WebViewJavascriptBridge.callHandler` 同义 shim；`registerHandler` 仅兼容无操作占位，不承诺原生 responseCallback。

document-start 的 top-only closure 保持端口私有，端口准备前最多缓存 32 条且总计 64 KiB；单条最多 64 KiB、handlerName 最多 80 字符。原生在 firstContentVisible / page-end 校验实际当前来源，再把端口发送给精确 origin 的主文档。禁止 `'*'`，禁止全 frame JavaScriptProxy；页面伪造 MessageEvent 和 iframe 发来的握手均拒绝。导航/隐藏/释放关闭两端原生句柄，旧 generation 永久失效；同文档重新显示会建立新端口。无法在早期注入成功的环境里不承诺首航早期消息可用，HTML opaque origin 也不从 baseUrl 伪造实际 sender 来源。

主文档投递证据：[OpenHarmony ArkWeb Controller API](https://github.com/openharmony/docs/blob/master/zh-cn/application-dev/reference/apis-arkweb/capi-web-arkweb-controllerapi.md) 的 postWebMessage 定义为发送端口到 HTML 主页面；[ArkTS NAPI 实现](https://github.com/openharmony/web_webview/blob/master/interfaces/kits/napi/webviewcontroller/napi_webview_controller.cpp) 的 postMessage 调用 [WebviewController::PostWebMessage](https://github.com/openharmony/web_webview/blob/master/interfaces/kits/napi/webviewcontroller/webview_controller.cpp)，最终调用同一 NWeb::PostWebMessage。生命周期要求见[华为应用与前端页面数据通道](https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/web-app-page-data-channel)。

## 本地验证

```bash
bash ohos/scripts/verify-har.sh
```

使用已有 DevEco Studio 的 Node、ohpm、hvigor 与 SDK，可用 `WEBVIEW_DEVECO_HOME` 指定相同工具目录。脚本依次运行源码 contract/security 检查、assembleHar、解包实际 HAR 的系统替身回归、ohpm prepublish，然后由独立 consumer 安装实际打包的 HAR 并编译公开 API。见 [VERIFICATION.md](../VERIFICATION.md)。这些检查不等于设备、权限弹窗、H5 时序或播放器验收。
