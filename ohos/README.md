# GY WebView 鸿蒙构建

当前 HAR 为 `1.2.0` 未发布候选，新增可选 HOST 权限说明与回执隔离，保留页面 URL 规范化、fileAccess/MIME 及 WindowStage 窗口策略，配套 system-actions-native `1.0.1`；新源码须重新编译和冻结。Release下载与Registry可安装性分别核对，详见[原生README](webview-native/README.md)。系统 ArkWeb + Kuikly 2.29.1，工程target/compatible为HarmonyOS API22；本机编译SDK为API26。完整五入口能力见[功能与平台差异](../docs/功能与平台差异.md)。

完整安装、公共 wire、能力限制与安全来源证据见 [HAR README](webview-native/README.md)。变更见 [CHANGELOG](webview-native/CHANGELOG.md)，本地验证见 [VERIFICATION](VERIFICATION.md)。

从仓库根目录执行：

```bash
bash ohos/scripts/verify-har.sh
```

脚本包含原生打包、ohpm prepublish 检查、实际 HAR 的独立消费者安装编译及 contract/security/lifecycle 检查。没有执行发布或设备验收。
