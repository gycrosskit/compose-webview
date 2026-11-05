# GY WebView 鸿蒙构建

当前 HAR 为 `2.0.1`，新增原生焦点归属与离页同步交接；Maven/Git Pod 保持 `2.0.0`，配套 system-actions-native `1.0.2`。Release 下载与 Registry 可安装性分别核对，详见[原生 README](webview-native/README.md)。工程 target/compatible 为 HarmonyOS API22，构建与设备验收分别记录。

完整安装、公共 wire、能力限制与安全来源证据见 [HAR README](webview-native/README.md)。变更见 [CHANGELOG](webview-native/CHANGELOG.md)，本地验证见 [VERIFICATION](VERIFICATION.md)。

从仓库根目录执行：

```bash
bash ohos/scripts/verify-har.sh
```

脚本包含原生打包、ohpm prepublish 检查、实际 HAR 的独立消费者安装编译及 contract/security/lifecycle 检查。没有执行发布或设备验收。
