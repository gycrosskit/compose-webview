# 更新记录

## HAR 1.0.2（未发布候选）

- 仅 OHOS HAR / OHPM 升级为 `1.0.2`，精确依赖 SystemActions Native `1.0.1`，使用新的 `har-1.0.2` 标签；Maven 与 iOS Git Pod 保留 `1.0.1`。
- Kotlin API 与原生实现不改；新依赖 HAR 须重新远程编译、冻结与消费验证，不复用旧候选或覆盖旧标签/摘要。

## 1.0.1 - 2026-10-25

- 为本轮最新 dev 配套候选使用新版本，保留既有 1.0.0 标签和制品摘要；源码验证、制品生产与公开消费重新执行，当前尚未发布。

## 1.0.0（正式版准备，尚未发布）

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
