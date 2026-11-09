# 更新记录

## 2.1.1

状态：Maven 候选，发布与精确远程消费待本轮回执确认。

- Android 安全 App Bridge 传输不受支持时报告 `CapabilityUnsupported(APP_BRIDGE)`，同一物理 WebView 一次；仍继续加载网页，不降级来源校验。提示样式、关闭交互与页面去重由宿主负责。
- 新增 `WebViewCapability.APP_BRIDGE`；穷举该枚举的调用方需补充分支。支持该能力的设备、普通网页和关闭 JavaScript 的页面行为保持。
- 仅 Maven/KMP 升级到 `2.1.1`；iOS Git Pod 保留 `native-2.1.0`，私有 OHPM/HAR 保留 `har-2.1.0`。既有 Tag、归档与摘要保持不变，设备验收单独记录。

## 2.1.0

状态：候选，发布与远程消费以各渠道回执为准。

- Android 与 OHOS 支持由宿主处理 H5 定位权限；iOS 按系统版本采用对应 WebKit 定位授权行为。权限、来源与生命周期边界见[功能与平台差异](docs/功能与平台差异.md)。
- 收紧 WebView 诊断日志，避免记录敏感 URL 与消息内容。
- Maven、Git Pod 和私有 OHPM/HAR 候选均为 `2.1.0`，实际发布与精确消费以各渠道回执为准；真机验收单独记录。

## 2.0.0

- 不兼容行为变更：三端文件／媒体选择改由宿主注入；旧构造器仍可编译，但未接线时取消请求，不再提供内置选择器。
- Android 注入 `AndroidWebFilePicker`，iOS 注册每 View 独立的 provider 工厂，鸿蒙覆盖 host selector/revoke；来源、权限说明、文档代次、MIME／大小和一次交付门禁仍由 WebView 保留。
- Maven、Git Pod（`native-2.0.0`）与私有 OHPM/HAR（`har-2.0.0`）统一使用 `2.0.0`；Renderer 范围和 SystemActions Native `1.0.2` 保持。迁移见[接入指南](docs/接入指南.md#200-文件选择迁移)，各渠道与设备验收分别记录，旧 Tag/checksum 不变。

## Native/HAR 1.2.1

- iOS Git Pod 使用 `native-1.2.1`，HAR/OHPM 使用 `har-1.2.1`；Maven 保留 `1.2.0`，不重发未修改的 KMP 产物。
- Renderer 版本由宿主选择：Pod 声明 `>= 2.29.1, < 3.0`，HAR 声明 `^2.29.1`；范围不代替实际消费验证。
- WebView 与独立 HAR 消费者均精确依赖 SystemActions Native `1.0.2`；导航、PiP、文件 chooser 与权限实现保持不变。
- 旧 Tag/归档/摘要保持不变；发布与精确公开消费状态见对应 Release，设备验收单独记录。

## 1.2.0（待发布）

- 新增可选 `WebViewPermissionPresentation.HOST`、权限说明请求/撤销事件与 `completePermissionExplanation` 单次回执；默认 NATIVE 保留原生说明。
- 修复三端权限取消/拒绝后的重试与设置恢复提示，隔离隐藏、离页、导航、销毁和跨实例的旧回执；系统申请与来源、frame、owner 复核仍由原生执行。
- Maven、Git Pod、HAR/OHPM 同步准备 1.2.0，沿用 Renderer 2.29.1 与 SystemActions Native 1.0.1；保留既有导航、PiP、文件选择及 HTTP/来源边界。
- 升级时 exhaustive `when (WebViewEvent)` 需处理新增说明事件；源码、制品、公开消费及真机验收分别记录。

## 1.1.0（历史版本，实际发行以不可变标签为准）

- KuiklyCompose 统一入口新增有类型的页面归属、PiP 保留与宿主扩展配置，保留旧参数调用兼容。
- 商城导航与能力来源独立，支持显式 HTTP(S) 域后缀及任意有效端口；修复跨域早期 Bridge 和文件选择返回的生命周期处理。
- Android、iOS、鸿蒙统一超时及 TLS 错误分类，保留原始错误码；iOS 验证工程统一最低版本为 15.0。
- Maven、Git Pod、HAR/OHPM 准备 1.1.0；PiP 与文件选择的真实设备业务验收仍独立记录。

## 1.0.2（未发布候选）

- Maven / iOS Git Pod / OHOS HAR / OHPM 统一准备 `1.0.2`，精确依赖 SystemActions Native `1.0.1`；Maven / Git Pod 使用新的 `1.0.2` 标签，HAR 使用新的 `har-1.0.2` 标签。
- Kotlin API 与原生实现不改；各渠道须按新版本重新远程编译、冻结与消费验证，不复用旧候选或覆盖旧标签/摘要。

## 1.0.1 - 2026-10-25

- 为本轮最新 dev 配套候选使用新版本，保留既有 1.0.0 标签和制品摘要；源码验证、制品生产与公开消费重新执行，当前尚未发布。

## 1.0.0

- iOS Native 支持由宿主显式保留活动视频页的系统 PiP 后台播放；默认隐藏仍停播，离页、关闭 PiP 和文档替换继续回收旧媒体会话。
- 此源码修订纳入 1.0.0 候选；发布前须重新核对候选归档与校验值，旧冻结归档不能作为当前源码的发布证据。

- Maven / Git Pod / HAR 统一准备 `1.0.0`；当前安装示例与默认消费者同步，历史渠道记录保持原样。
- Kuikly 保持 `2.29.1` 系列、Kotlin 保持 `2.2.21-1.0.0`。
- Jenkins、冻结归档、精确公开消费及 Registry 安装待本轮真实收据；不代表设备或宿主业务验收。

## Unreleased

- Kuikly KMP 与宿主统一为 `2.29.1-2.0.21-ohos`，配套 iOS/OHOS Renderer 固定 `2.29.1`；同步配置与锁文件，后续随宿主升级。尚未发布，完整 SDK 与消费验证分别记录。

## 0.3.0-rc.1（RC 准备，尚未发布）

- 删除根 `compose-webview` CMP 产品及根包 `AppWebView`、`AppWebViewState`、`WebViewCallbacks` API；保留 `webview-core` / `webview-kuikly`，共 12 个 Maven publication、KuiklyCompose / runtime 与 native 接线。
- Git Pod `GYWebView` 固定 tag / Pod `0.2.0-rc.18`；WebView HAR 保持 `0.2.0-rc.14`，system-actions-native HAR 保持 `0.2.0-rc.4`；不重发未修改的原生产物。
- 完整归档、冻结 checksum、远程源码 CI 与精确公开消费须由真实发布收据确认，历史 tag / checksum 保持不变。
- 迁移与验证边界见 [RC 迁移说明](docs/RC迁移说明.md)。
