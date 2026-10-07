# 0.2.0-rc.12

- v2 未发布候选：pageMessageChannels 采用初始主文档私有 capability、文档 nonce 与单次回复；隐藏/导航/停止/销毁撤销，显式 reload 重建物理 owner。64KiB UTF-8 / 128 pending，与 common 合同一致。
- 默认空通道保留 rc.11 的 Bridge、完整 URL 导航、文件、媒体和全屏行为。
- 配套 Maven / iOS Git Pod rc.13；OHOS 不声称实现早期双向页面通道，SystemActions Native 仍精确 rc.4。

# 0.2.0-rc.11

- 业务脚本允许明确 pageBridge 的初始 HTTP(S) 同源主文档，保留 frame、nonce 和文档代次门禁，不授予文件/媒体高权限。
- 增加 navigationPolicy.allowedUrls 完整 URL 原字符串白名单，与 scheme、origin、blockedRules 同步组合，不影响子资源。
- 补齐 CMP/Kuikly iOS 与 OHOS 原生透明背景，保留 rc.10 的 OhosWebViewDataCleaner。
- Maven、Native Git Pod 与 HAR 统一 rc.11；system-actions-native 仍精确 rc.4。

# 0.2.0-rc.10（候选，尚未发布）

- 导出无状态 `OhosWebViewDataCleaner`：资源缓存与网站数据分别清理；等待 Cookie 删除回执，保留系统异常。
- 宿主负责 UI 线程、Web 初始化、清理时机及取消/销毁回执；WebStorage 同步返回不作为持久化或业务退出证明。
- Maven / HAR 候选统一 rc.10；未修改的 iOS Native Pod 保持 rc.9，system-actions-native rc.4 / Kuikly Render 2.28.0 不变。

# 0.2.0-rc.9（候选，尚未发布）

- CMP/Kuikly 的 Bridge、原生回执和弹窗按当前 owner、文档代次与可见性撤销；禁用 JavaScript 不再注入声明式脚本。
- iOS15 常规 Web 可用，18.4+ 使用公开原生文件代理；iOS/HarmonyOS capture 复用系统拍摄，核验权限、MIME/大小与临时文件生命周期。
- Android 拍摄拒绝空/错格式/超限输出；成功文件保留至文档撤销，后续 chooser 取消不会提前删除。
- HostSuffix 支持协议、排除父域与可选 userinfo 拒绝的组合；仅导航策略变化保留当前 Controller/DOM；system-actions-native 精确依赖 rc.4。

# 0.2.0-rc.8（候选）

- 当前文档隐藏时仍执行初始化脚本，隐藏队列拒绝/清空业务消息；显示先同步 JS 可见状态，再恢复活动状态与 Bridge 端口，不重载页面。
- 旧 JS visibility revision、旧 Controller/generation 完成不能恢复隐藏队列；导航撤销等待标记，新文档不会被旧 Promise 锁住。
- DOM_READY 在 document-start 注册真实 DOMContentLoaded，page-visible/page-end 安全兜底；命名脚本每文档执行一次。
- 保留可信主文档、JavaScript 开关、旧 render/generation、隐藏业务消息与权限门禁；Native Pod 保持 0.2.0-rc.7。

# 0.2.0-rc.5

- 精确依赖 system-actions-native 0.2.0-rc.3，使宿主与 Web HAR 使用同一窗口 owner 和 layout-only 方向恢复修复。Web HAR 原生源码与 rc.4 相同；Swift Pod 保持 rc.4。

# 0.2.0-rc.4

- Maven 空资源变体与 POM license 发布修复；HAR/Pod 与 Maven 同版本，运行时代码与 0.2.0-rc.3 相同。

# 0.2.0-rc.3

- 全屏窗口执行迁入 system-actions 0.2.0-rc.2 共用 owner，宿主仅注入方向/系统栏策略。
- 同 render 主动退出确认前拒绝新的全屏，防止旧 exit 回调关闭新 handler。

# 变更记录

## 0.2.0-rc.2（预发布）

- 增加 `GYWebView`，自行封装系统 ArkWeb 与 Kuikly 原生 View。
- 对齐公共 WebViewRequest、导航规则、JSON 方法回调和统一 H5 Bridge 协议。
- 每次 request 重建原生实例，同步首航/导航策略，精确 origin 主文档 MessagePort，默认关闭高风险能力。
- 支持附着后 HTML / baseUrl、history、脚本、系统文件选择与媒体权限、全屏窗口恢复、隐藏/销毁撤销迟到回调。
- 修复首航 loadUrl/loadData 抛错后的 reload：重建 Controller 后重交原请求一次，正常 reload 保留 refresh。
- 增加独立于 JS/Bridge 的 allowedOrigins 主帧精确来源白名单，与 blockedRules 组合。
- capture 明确回报 CapabilityUnsupported(FILE_CAPTURE)，文件结果一次结算并拒绝迟到 URI。
- 提供实际 HAR 打包、ohpm prepublish、独立产物消费者编译和安全/lifecycle 检查；尚未设备验收或发布。
