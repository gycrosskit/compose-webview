# 0.3.0-rc.1 RC 迁移说明

本次仅准备 Maven `0.3.0-rc.1` RC，尚未发布；真实归档摘要、固定源码 SHA、远程 CI 与公开消费结果由本轮发布收据记录。没有新 checksum 时发布校验直接失败，不复用旧版本或填写占位摘要。

## 从旧 CMP 产品迁移

- 删除根 `compose-webview` CMP 产品及根包 `AppWebView`、`AppWebViewState`、`WebViewCallbacks` API，属于不兼容变更；不能仅替换版本号升级旧 CMP 调用方。
- 旧 CMP 使用方继续锁定已发布的旧版本及其原 tag 文档，或先迁移到 Kuikly raw / KuiklyCompose / native 入口。KuiklyCompose 和 compiler / runtime 仍保留，宿主统一匹配 Kuikly / Renderer。
- 新版本只提供 `webview-core` / `webview-kuikly`，共 12 个 Maven publication；根聚合工程不补兼容空壳或伪 Maven 坐标。
- Git Pod `GYWebView` 固定 tag / Pod `0.2.0-rc.18`；WebView HAR 保持 `0.2.0-rc.14`，system-actions-native HAR 保持 `0.2.0-rc.4`。Maven RC 版本不能替代原生 tag 或 Pod 内部版本。

## 发布与验收边界

远程 macOS 按现有脚本打包，metadata 正规化后检查精确 publication、POM 许可、平台变体、实际文件与 MD5 / SHA-1 / SHA-256 / SHA-512 sidecar，保存真实 SHA-256 与源码身份。旧发布 tag、归档及 `release-checksums.txt` 既有行保持不变。

新 RC 只作为 prerelease。发布后按精确版本从 JitPack 验证完整公开 inventory / metadata / 文件与 Android、iOS、OHOS 实际受支持变体，iOS 按上面的独立原生版本组合消费；不使用 `mavenLocal`、staging 或 source substitution 充当公开消费。新坐标或 root metadata 指向的真实文件缺失必须失败，不回退旧 CMP 制品或旧版本。

本地仅运行 Python / Bash / YAML 轻量检查，不执行 Gradle、Swift / Xcode 或 Hvigor 重编译。CI、远程消费与真实设备 / SDK / 业务验收分别记录；本次不覆盖设备验收。
