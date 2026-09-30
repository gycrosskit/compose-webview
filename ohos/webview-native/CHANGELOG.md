# 变更记录

## 0.2.0-rc.1（预发布）

- 增加 `GYWebView`，自行封装系统 ArkWeb 与 Kuikly 原生 View。
- 对齐公共 WebViewRequest、导航规则、JSON 方法回调和统一 H5 Bridge 协议。
- 每次 request 重建原生实例，同步首航/导航策略，精确 origin 主文档 MessagePort，默认关闭高风险能力。
- 支持附着后 HTML / baseUrl、history、脚本、系统文件选择与媒体权限、全屏窗口恢复、隐藏/销毁撤销迟到回调。
- 修复首航 loadUrl/loadData 抛错后的 reload：重建 Controller 后重交原请求一次，正常 reload 保留 refresh。
- 增加独立于 JS/Bridge 的 allowedOrigins 主帧精确来源白名单，与 blockedRules 组合。
- capture 明确回报 CapabilityUnsupported(FILE_CAPTURE)，文件结果一次结算并拒绝迟到 URI。
- 提供实际 HAR 打包、ohpm prepublish、独立产物消费者编译和安全/lifecycle 检查；尚未设备验收或发布。
