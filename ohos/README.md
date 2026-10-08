# GY WebView 鸿蒙构建

正式HAR版本说明以[原生README](webview-native/README.md)为准；2026-10-08未发布源码候选含fileAccess/MIME等修复，不能用相同版本号推定远程包含候选diff。系统ArkWeb + Kuikly2.28.0，工程target/compatible为HarmonyOS API22；本机编译SDK为API26。完整五入口能力见[功能与平台差异](../docs/功能与平台差异.md)。

完整安装、公共 wire、能力限制与安全来源证据见 [HAR README](webview-native/README.md)。变更见 [CHANGELOG](webview-native/CHANGELOG.md)，本地验证见 [VERIFICATION](VERIFICATION.md)。

从仓库根目录执行：

```bash
bash ohos/scripts/verify-har.sh
```

脚本包含原生打包、ohpm prepublish 检查、实际 HAR 的独立消费者安装编译及 contract/security/lifecycle 检查。没有执行发布或设备验收。
