# 鸿蒙验证记录

日期：2026-09-30。工作分支：`codex/kuikly-webview`。候选：`@gycrosskit/webview:0.2.0`（未发布）。

## 已执行

使用本机已有 `/Applications/DevEco-Studio.app/Contents` 中的 Node、ohpm、hvigor 与 SDK。编译和最低系统配置均为 HarmonyOS 6.0.2 / API 22；Kuikly Render 2.28.0。

| 检查 | 结果 |
|---|---|
| `node ohos/tests/contract.test.cjs` | PASS，源码系统替身检查 |
| `node ohos/tests/contract.test.cjs --har .../WebViewNative.har` | PASS，解包实际 HAR 的系统替身检查 |
| `ohpm install --all` + `WebViewNative@default assembleHar` | BUILD SUCCESSFUL |
| `ohpm prepublish WebViewNative.har` | `prepublish @gycrosskit/webview 0.2.0 succeed` |
| 独立 consumer 以 `file:../../webview-native/build/default/outputs/default/WebViewNative.har` 安装产物 | install completed |
| consumer 只从 `@gycrosskit/webview` 导入公开类型/Kuikly base 并 `WebViewConsumer@default assembleHar` | BUILD SUCCESSFUL |

入口命令：`bash ohos/scripts/verify-har.sh`。消费者没有源码路径依赖，也没有补装 Render 直接依赖掩盖公共类型问题；HAR 入口重导出了注册需要的 Kuikly 类型。

运行检查分别转译源码和实际 HAR 解包得到的 `WebViewWire.ts` / `GYWebView.ets`，用平台 stub 验证同步策略、新窗口取消与回调时序，并执行实际注入的 Bridge closure。覆盖：非法 scheme/凭据/相似域/端口、默认 JS 关闭时拒绝 Bridge、附着前 HTML 等待/baseUrl/historyUrl、一次性内部 data 导航、JSON history 回调、独立原生实例 token、可信主文档精确 origin 通道、iframe/伪造握手拒绝、隐藏后重新握手、旧通道/旧 eval/request/dispose 迟到消息拒绝、媒体授权与文件选择销毁结算、fullscreen 返回消费状态和原窗口方向/布局恢复。

## 本轮 Issue 回归

- #6 在修改前对旧实际 HAR 执行新增回归，先失败于 `failed first load must rebuild Controller`。修复后重建 HAR 并执行相同回归转绿：URL headers / HTML baseUrl、encoding、historyUrl 保留，新 attached 只提交一次；旧 attached、新请求和销毁后不重交旧内容；正常 PageStarted 后 reload 使用原 Controller 的 refresh。
- #4 JS=false、Bridge=false 的 HTTP/HTTPS 首航与同源路径允许；scheme、host、有效端口变化和伪后缀域拒绝。来源白名单与商城 blockedRules 组合，原生同步报告 blocked Navigation。HTML 仅首个无手势内部 data 首航例外，后续和用户手势 data 拒绝。
- #5 可信就绪文档 capture 发出 typed unsupported 且不申请拍摄权限；同步系统结果抛错也仅调用一次。非可信文档拒绝，普通 picker 在导航、请求切换、隐藏和销毁时完成空列表，迟到文件 URI 丢弃。

本轮完整入口日志：`build/ohos-prerelease-fixes.log`，未执行设备拍摄或真实 H5 验收。

## 编译警告

Render 2.28.0 发布声明存在 `arkts-strict-typing-required` 警告，Kuikly Builder 输入转换为 `@ObjectLink` 时存在 SDK 观察属性警告。HAR 无签名配置、consumer 本地 file 依赖警告符合本地库验证用途。hvigor 报的 SemVer 警告未阻止 ohpm 对 0.2.0 预检成功。没有关闭类型检查或修改 SDK。

## 未执行

没有设备安装、H5/原生消息端口真实握手、网络重定向、HTML opaque/baseUrl 来源行为、权限弹窗/系统设置、文件选择、视频播放/旋转/宿主窗口布局的设备验收；没有 ohpm publish、commit、push、Tag 或 Release。编译和 stub 检查证明可构建与策略/撤销逻辑，不代替这些真实设备结果。
