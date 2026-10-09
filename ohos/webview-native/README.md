> 当前HAR版本为 `0.2.0-rc.14`，配套 system-actions-native `0.2.0-rc.4`。v2 实现 pageMessageChannels 初始主文档 capability 与单次回复；私有安全随机 owner key 和 document nonce 不进入事件、日志或返回值。默认空配置保留既有行为。本地 HAR 检查、远程发布与设备验收分别记录。

# GY WebView 鸿蒙 HAR

`@gycrosskit/webview` 的 **0.2.0-rc.14 预发布版**。本组件自己封装系统 ArkWeb，供 Kuikly 2.28.0 使用，最低 HarmonyOS 6.0.2 / API 22。依赖 `@kuikly-open/render:2.28.0` 与 `@gycrosskit/system-actions-native:0.2.0-rc.4`，后者提供共用窗口执行 owner。

rc.14 修复初始页面 URL 规范化：scheme/host 大小写、默认端口和空路径按同一文档身份匹配；path/query/fragment 保留原字节，allowedUrls 仍匹配实际 URL 原字符串。主帧 capability、nonce、owner 与单次回复门禁保持有效。

## 安装与注册

HAR 0.2.0-rc.14 配套 system-actions 0.2.0-rc.4。宿主直接使用系统组件时也选择 rc.4；共用窗口 owner 来自该包的 `WindowPolicyController.shared`，不得同时加载两个版本。Release HAR 与 Registry 分别验收。

## 安装

以下为本版本精确 Registry 坐标；审核通过并实际可查询、安装后使用。

```bash
ohpm install @gycrosskit/webview@0.2.0-rc.14
```

本地工作树可从assembleHar输出验证；正式版本可从同版本 GitHub Release 下载 Web HAR 并校验 SHA-256，system-actions 固定 Registry rc.4。本地干净消费者核对只有一份窗口 owner。完全离线时下载同版本 Web 和 system-actions rc.4 的固定 Release HAR、校验各自 SHA，并用 root override 保证同一系统包。文件下载消费与 Registry 安装分开验收。

```json
{
  "dependencies": {
    "@gycrosskit/webview": "file:./libs/WebViewNative.har",
    "@gycrosskit/system-actions-native": "0.2.0-rc.4"
  },
  "overrides": {
    "@gycrosskit/system-actions-native": "file:./libs/SystemActionsNative.har"
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

## 共享 Web 数据清理

清理器独立于 Kuikly View / Module，现有宿主原生 Module 可直接复用。调用前由宿主保证 UI 线程与 Web 组件已加载；影响应用共享资源缓存以及默认非隐私 Cookie / WebStorage，不按 View 或域名隔离。

```ts
import { OhosWebViewDataCleaner } from '@gycrosskit/webview';

OhosWebViewDataCleaner.clearResourceCache(); // 内存/磁盘缓存，保留 Cookie 与网站存储。
await OhosWebViewDataCleaner.clearWebsiteData(); // 缓存 → Cookie 异步完成 → WebStorage。
```

系统异常原样抛出 / reject，失败即停止后续步骤；部分删除不会回滚。缓存和 WebStorage 的 void API 没有删除完成信号，Promise 完成仅说明 Cookie 回执与其余 API 返回，不证明业务退出、持久化完成或所有内核数据类型删除。宿主决定切环境/退出时机、清理自身状态并停止页面继续写入；不承诺隐私模式清理。

取消等待与宿主销毁不撤销已发起删除。现有 Kuikly Module 在有效 requestId 下交付结果，cancel/onDestroy 后过滤迟到完成；不把普通同步缓存返回转换成网页账号退出成功。无需注册新的 Kuikly Module。

## wire 与安全

原生 View 名为 `GYWebView`。`request` 接收公共 `WebViewRequest` JSON 字符串，另有 `visible: Boolean` 和 `onEvent`。请求包含 `content`、`settings`、`security`、`scripts`、`blockedResourceRules`、`navigationPolicy`，与 Kotlin adapter 一致。每个新 request 创建独立 WebviewController / Web 节点；旧节点事件通过实例 token 拒绝，旧异步操作通过 generation 撤销。

原生在加载初始 URL、HTML baseUrl / historyUrl、导航与子资源时同步执行策略。顶层只加载 HTTP / HTTPS，拒绝 URL 用户名密码、file/content/resource、自定义 scheme 和页面 data 导航。allowFileAccess=true只允许受规则约束的沙箱file子资源。HTML 等待 controller attached 后调用 `loadData`；只允许当前loadData的一次无用户手势、精确匹配声明MIME的内部data主文档导航，保留baseUrl、encoding、mimeType、historyUrl。导航事件仅用于报告同步判定结果。`navigationPolicy.allowedOrigins` 为可选 HTTP(S) URL 数组，空数组或省略时不限制主帧来源；非空时精确比较 scheme、host 和有效端口，与 JavaScript/Bridge 开关独立，并与主帧 `blockedRules` 组合。

rc.11 增加 `navigationPolicy.allowedUrls`：非空数组只允许完整原字符串匹配的主文档 URL，与来源及拒绝规则组合，不归一化路径、查询、fragment 或默认端口，不影响子资源。明确 `pageBridgeEnabled` 的初始 HTTP(S) 同源页面可执行仍受主文档门禁的业务脚本；HTTP 不进入高权限信任。原生 Web 节点背景透明，H5 自身背景仍由页面 CSS 控制。

JavaScript、Bridge、文件 URL、媒体采集默认关闭。TLS 错误调用 `handleCancel()`，网络/HTTP 错误只把主文档失败作为整页错误。TLS 回调没有主框架字段，TLS 拒绝仍会上报安全错误。HTML 无可验证 HTTP 来源时，不能获得 Bridge、文件或媒体授权。

| 能力 | 实际实现与限制 |
|---|---|
| URL / HTML | 首航 headers 仅随顶层 `loadUrl` 传入；相对资源使用 HTML baseUrl |
| history / 方法 | 首航 loadUrl/loadData 抛错后 reload 重建 Controller/Web 节点，新 attached 原样重交 URL headers 或 HTML base/history 一次；已提交文档 reload 继续 refresh 保留历史；goBack 优先退出全屏；goForward、stopLoading、exitFullscreen |
| evaluateJavascript | 必须 JS 开启、文档就绪且当前 HTTPS 来源可信，或处于低权限 pageBridge 初始同源；输入 `{script}`，输出 `{result: string|null}` |
| scripts | DOCUMENT_START/DOM_READY 在 document-start 注册；DOM_READY 等待真实 DOMContentLoaded，page-visible/page-end 兜底；DOCUMENT_FINISHED 在 page-end。隐藏仍初始化，每个 id 每文档一次；仅作用 top，JS/可信门禁保留 |
| Bridge | 主文档独占 MessagePort；appBridge 只允许可信 HTTPS；pageBridge 只允许初始精确 HTTP(S) 同源 |
| 媒体采集 | CAMERA/MICROPHONE，可信来源与当前主文档精确同源；系统授权后再次校验当前 request 与 generation。其他资源拒绝 |
| 文件选择 | 可信且就绪当前主文档的系统 DocumentViewPicker，单选或最多 10 项多选；capture 使用 CameraPicker 输出 JPEG/MP4，需同时启用媒体采集并授予权限。每项核验 accept/type 和 1 byte～50 MiB 大小。系统事件不提供来源 frame |
| 全屏 | 单个 owner，保存并恢复原窗口 layoutFullScreen 与方向（包括 UNSPECIFIED）；尺寸为横屏视频时使用 AUTO_ROTATION_LANDSCAPE。宿主使用 fullscreenChanged 调整 Kuikly 页面布局 |
| 可见性 / 释放 | 隐藏、导航、请求切换、render 退出及 onDestroy 撤销消息端口、系统请求和迟到回调；隐藏停止媒体、onInactive，显示后 onActive 并重新握手，不重载或重复初始化 |

DocumentViewPicker/CameraPicker 结果在导航、隐藏、request 切换和销毁时以空列表结算；迟到回调不能给新文档交付 URI。拍摄使用本实例沙箱 cache 文件，不自动写系统相册；成功后文件保留到文档撤销，失败/取消立即删除，并核验实际输出 JPEG/MP4 头、大小和系统返回 URI。当前 SDK 缺少真实来源 frame 和独立 user-gesture 字段，不能把当前可信页面检查写成来源 frame 证明。真实设备权限与 H5 上传仍需宿主验收。

支持 DOM Storage、图像访问、zoomAccess、混合内容和 cacheMode、User-Agent suffix。HAR rc.13包含系统字体倍率与算法暗化；Android 专属缩放按钮、viewport/overview 没有等价 ArkWeb 开关。新窗口事件同步取消实际第二窗口，policy 允许时路由到当前页面；自动脚本开窗另外要求显式开关。顶层file/content URL与content/resource子资源拒绝；本版显式allowFileAccess可允许file子资源。每实例第三方Cookie开启请求仍拒绝；false/默认也不保证per-view隔离，同进程其他Web可能修改全局Cookie策略。

所有方法回调都为 JSON 对象 `{result: ...}`，不返回裸布尔字符串。`reload/goBack/goForward/stopLoading/exitFullscreen` 的 params 为 null；`evaluateJavascript` 的 params 为 JSON 字符串。historyChanged 通过 `onEvent` 报告 canGoBack / canGoForward。异步调用被撤销后不再投递旧结果。

## H5 Bridge

```js
GYWebViewBridge.postMessage('handlerName', { value: 'data' });
```

载荷为 `handlerName` + 原始 `data` 字符串，组件不解释命令。保留 `JSAndroidBridge.handleJSBridgeMessage` / `WebViewJavascriptBridge.callHandler` 同义 shim；`registerHandler` 仅兼容无操作占位，不承诺原生 responseCallback。

document-start 的 top-only closure 保持端口私有，端口准备前最多缓存 32 条且总计 64 KiB；单条最多 64 KiB、handlerName 最多 80 字符。原生在 firstContentVisible / page-end 校验实际当前来源，再把端口发送给精确 origin 的主文档。禁止 `'*'`，禁止全 frame JavaScriptProxy；页面伪造 MessageEvent 和 iframe 发来的握手均拒绝。导航/隐藏/释放关闭两端原生句柄，旧 generation 永久失效；隐藏初始化不入业务队列，已有队列同时撤销。恢复先完成 JS 状态同步再握手，JS revision 拒绝同文档晚执行的旧可见性更新；原生 visible/origin/generation 始终是授权门禁，页面内状态只是队列管理。同文档重新显示会建立新端口。无法在早期注入成功的环境里不承诺首航早期消息可用，HTML opaque origin 也不从 baseUrl 伪造实际 sender 来源。

主文档投递证据：[OpenHarmony ArkWeb Controller API](https://github.com/openharmony/docs/blob/master/zh-cn/application-dev/reference/apis-arkweb/capi-web-arkweb-controllerapi.md) 的 postWebMessage 定义为发送端口到 HTML 主页面；[ArkTS NAPI 实现](https://github.com/openharmony/web_webview/blob/master/interfaces/kits/napi/webviewcontroller/napi_webview_controller.cpp) 的 postMessage 调用 [WebviewController::PostWebMessage](https://github.com/openharmony/web_webview/blob/master/interfaces/kits/napi/webviewcontroller/webview_controller.cpp)，最终调用同一 NWeb::PostWebMessage。生命周期要求见[华为应用与前端页面数据通道](https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/web-app-page-data-channel)。

## 本地验证

```bash
bash ohos/scripts/verify-har.sh
```

使用已有 DevEco Studio 的 Node、ohpm、hvigor 与 SDK，可用 `WEBVIEW_DEVECO_HOME` 指定相同工具目录。脚本依次运行源码 contract/security 检查、assembleHar、解包实际 HAR 的系统替身回归、ohpm prepublish，然后由独立 consumer 安装实际打包的 HAR 并编译公开 API。见 [VERIFICATION.md](../VERIFICATION.md)。这些检查不等于设备、权限弹窗、H5 时序或播放器验收。


全屏原生事件限制：SDK 的 `onFullScreenExit` 没有 handler/id。主动调用 `exitFullscreen` 后，
同一 render 在收到原生退出确认前拒绝下一次全屏并退出新 handler，避免旧通知释放新 lease。
`nativeFullscreenExited(renderToken)` 先确认已主动退出的代次；系统/网页自行退出时再释放当前 lease。
Window 的恢复不等待该事件，也不设置超时猜测归属；若 SDK 没有确认，同 render 的再进入保持拒绝。
新 request 创建新 Controller/render 时清门禁，旧 token 事件仍被过滤；销毁不会再交付事件或操作新 owner。

HVigor 打入 HAR 的生成 lock 记录构建时的相对 override 缓存路径；包内公开 oh-package manifest 保持精确版本依赖。调用方 root override 指向自己下载并校验的 rc.3 HAR，不依赖发布机器缓存目录。新目录独立消费者 30/30 tasks、actual HAR 契约与唯一 owner realpath 检查通过，迁移结果见闭合记录，设备未验。

rc.9 的 HostSuffix 可声明 `scheme="https"`、`includeRoot=false`、`rejectUserInfo=true`，最后一个条件默认 false 保留旧规则行为；空 `@` 同样拒绝。仅 navigationPolicy 变化保留当前 ArkWeb Controller/DOM 和已授权能力请求；security 变化仍重建，商城规则不授予 Bridge 能力。

具名通道不等待 PageVisible/PageEnd；非空频道用 API15 `runJavaScriptOnDocumentStart` 按数组顺序先安装 facade；Bridge 和每个调用方脚本仍是独立 ScriptItem。空频道继续原 legacy 注入数组。自身 iframe 无 capability 被拒绝；可访问 parent facade 的同源 iframe 使用父文档 capability，与 Android/WK 一致，不提供 JavaScript 调用栈隔离。后续导航、隐藏、stop、失败或销毁永久撤销；show/H5 reload 不恢复，显式 reload 以新 Controller 重建。每条 UTF-8 64 KiB、最多 128 待回复，旧 replyId/nonce/owner 不可跨文档使用。

本地源与实际 HAR 合同检查使用生产 ETS/生成 JS，系统 SDK 由测试替身隔离；API22 配置由本机 SDK 编译。不等于真 ArkWeb/设备验收，仍需确认 document-start 的同步 Proxy/URL query 与首段脚本实际时序。

OHOS `replyPageMessage` Boolean 与 A/i 一致：通过当前 owner/nonce/input/单次 replyId 检查并提交原生 JS 队列即 true；不存在 onmessage、H5 handler 抛错或异步 JS 拒绝不改写已受理结果。它不是交付确认或业务 ACK。同步 SDK 提交失败返回 false；旧 ID、撤销或新文档返回 false。

## 0.2.0-rc.13 平台能力与限制

固定源码基线、五入口差异和验证范围见[功能与平台差异](../../docs/功能与平台差异.md)；本节记录自HAR rc.13提供的既有能力，Release产物消费与Registry上架分别核验。

允许的 popup 复用当前页面，保留原有导航、可信来源、手势/自动开窗和 owner 门禁；不创建独立第二窗口。`textZoomRatio` 使用系统字体倍率和请求上下限，`darkMode` 跟随系统，`forceDarkAccess` 由算法暗化开关控制。系统配置观察失败不阻断加载，销毁后撤销观察和迟到事件。

本版 allowFileAccess 显式映射 ArkWeb fileAccess（默认 false）；true 只放行应用沙箱 file 子资源，顶层仍限声明 HTTP(S) policy，blockedResourceRules 仍优先，content/resource URI不开放。第三方 Cookie 当前只有 WebCookieManager 全局静态开关，组件不修改进程共享策略；false/默认也不能保证同进程其他Web修改全局策略后的每实例隔离。

候选 HTML MIME 使用 ArkWeb loadData 已有 Media type 参数，移除 text/html 人为限制；仅接受token/token MIME，不接受参数，charset走独立encoding字段；声明 MIME 的一次性内部data导航必须精确匹配类型，后续/手势data仍拒绝。historyUrl需非空baseUrl（系统声明base为空时history无效，组件明确拒绝）；真实引擎的各charset/中文渲染仍需设备验收。
