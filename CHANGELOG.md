# 更新记录

## 0.3.0-rc.1（RC 准备，尚未发布）

- 删除根 `compose-webview` CMP 产品及根包 `AppWebView`、`AppWebViewState`、`WebViewCallbacks` API；保留 `webview-core` / `webview-kuikly`，共 12 个 Maven publication、KuiklyCompose / runtime 与 native 接线。
- Git Pod `GYWebView` 固定 tag / Pod `0.2.0-rc.18`；WebView HAR 保持 `0.2.0-rc.14`，system-actions-native HAR 保持 `0.2.0-rc.4`；不重发未修改的原生产物。
- 完整归档、冻结 checksum、远程源码 CI 与精确公开消费须由真实发布收据确认，历史 tag / checksum 保持不变。
- 迁移与验证边界见 [RC 迁移说明](docs/RC迁移说明.md)。
