# WebView 0.2.0 候选验证

日期：2026-09-30。独占 Worktree `gycrosskit-kuikly-webview`，任务分支 `codex/kuikly-webview`。本轮预发布 Maven / Pod / HAR 版本统一为 `0.2.0-rc.2`；历史本地候选结果保留，远程结果另行记录。

## 已执行

| 检查 | 结果 |
| --- | --- |
| 原 CMP Android 测试 | 16 项，0 failure/error |
| webview-core 契约、策略、wire、Android 能力/Bridge 测试 | 42 项，0 failure/error |
| root/core/Kuikly Android、iOS arm64/SimulatorArm64 编译 | 通过 |
| core/Kuikly OHOS 编译 | 通过 |
| 全部 Maven publications 写入独立 staging 并打包 | 通过，17 个 module metadata；AAR、iOS x64/arm64/simulator、OHOS KLIB 均检查 |
| 独立 Maven Kuikly consumer APK / Manifest 合并 / D8 | 通过 |
| Kuikly consumer 依赖门禁 | 无 Compose UI/runtime；AndroidX Activity 的 runtime-annotation 两个注解模块除外 |
| 独立 Maven Kuikly consumer iOS SimulatorArm64 compile | 通过 |
| 独立 Maven Kuikly consumer iOS arm64 动态 Framework 与真实 Render 链接 | 通过 |
| 独立 Maven Kuikly consumer OHOS `.so` 链接 | 通过 |
| 同时消费原 CMP 入口与 Kuikly 的 Android APK/D8、iOS SimulatorArm64 compile | 通过 |
| Objective-C adapter 编译、静态库独立消费者与真实 Render 最终 executable 链接 | 通过 |
| Swift 消费原生公开协议 / Kotlin 脚本生成一致性 | 通过 |
| 鸿蒙 security/lifecycle 检查、实际 HAR、ohpm prepublish、实际 HAR 独立消费 | 通过，详见 [鸿蒙记录](ohos/VERIFICATION.md) |
| 空 release checksum 拒绝安装未发布版本 | 通过，下载前失败 |

Maven 校验包含 group/version、跨模块依赖、available-at、component 引用、实际文件大小与 SHA-256，以及已有 module checksum。使用 Maven SNAPSHOT 元数据解析时间戳文件，忽略已被新快照替代的旧文件。独立消费者没有 project/includeBuild/mavenLocal 依赖。

iOS 使用本机真实 OpenKuiklyIOSRender 2.28.0 iphoneos Framework；没有虚构 C 符号、未允许未定义符号绕过链接。最终链接只证明类型与符号可用，没有启动真实设备。

## 复验入口

```bash
WEBVIEW_IOS_RENDER_FRAMEWORK_DIR=/path/to/parent/of/OpenKuiklyIOSRender.framework bash scripts/verify.sh
```

脚本使用已有 Android SDK 和 DevEco Studio；各平台可以分别执行 `scripts/package-maven.sh`、`scripts/verify-ios-native.sh`、`ohos/scripts/verify-har.sh`。本次按这些步骤分别执行，以避免重复全部构建。

日志保存在忽略的 `build/`：`package-maven.log`、`consumer-kuikly.log`、`consumer-cmp.log`、`native-ios/verify.log`；首次源码编译/测试与重发布日志位于 `/tmp/gy-webview-build.log`、`/tmp/gy-webview-final-publish.log`。测试 XML 在 root/core 的 `build/test-results/`。

## 发现并修复的问题

- 将公共模型与 Android 原生能力放入 core，原 CMP 根模块和 Kuikly 依赖它，避免 Kuikly 被迫带入 CMP UI/runtime；Android namespace 分离，独立 APK 检查重复类与资源。
- Kuikly 原生导航须同步决策，不能等 Kotlin 事件返回。Bridge 使用原生 frame/source 或鸿蒙主文档独占通道，加上文档 generation 撤销旧请求；统一 H5 入口保留历史协议，未伪造 responseCallback。
- 文件、媒体、eval 和全屏的 owner 与迟到回调按导航、隐藏、切换请求和销毁撤销；不以旧回调给新页面重新授权。
- 本地打包脚本补齐 Android SDK 环境；Maven 检查支持标准 SNAPSHOT 时间戳。独立 Kuikly 验证工程仅在混合 CMP 模式应用 Compose compiler，避免无 runtime 的错误配置。

## prerelease Issue 修复

#4 增加与 JS/Bridge 独立的 `allowedOrigins` 主帧精确来源白名单；#5 鸿蒙 capture 明确发出 `CapabilityUnsupported(FILE_CAPTURE)`，文件结果一次结算并丢弃迟到 URI；#6 首航 throw 后 reload 用独立 Controller 重交原 URL/HTML 一次，正常提交后仍 refresh。鸿蒙在旧实际 HAR 上先红，再对修复后的实际 HAR 转绿，并通过 assembleHar、ohpm prepublish 和独立 HAR 消费编译。详见 [鸿蒙回归](ohos/VERIFICATION.md)，日志 `build/ohos-prerelease-fixes.log`。 Kotlin 的 NavigationPolicy/Wire/Policy 定向测试 11 项通过，core/Kuikly/CMP Android 编译与 CMP iOS arm64 编译通过；iOS 原生脚本生成一致性、Objective-C 编译、真实 Render 最终链接与 Swift 消费 API 检查通过。日志 `build/navigation-policy-verification.log`、`build/navigation-policy-ios-native-verification.log`。

## 未执行与限制

- 无设备安装、真实 H5 Bridge/MessagePort 时序、权限弹窗、系统设置、文件选择、媒体播放、后台恢复、旋转与宿主全屏验收；平台 stub 检查不能替代设备行为。
- Kuikly Native DSL 为本轮入口；Kuikly Compose DSL 未验收。未修改真实应用的接入或资源。
- iOS 15.0 为最低系统；原生文件选择回调从 18.4 提供，旧系统拒绝开启文件选择的请求。DOM 交互拦截不是原生安全隔离保证。
- 鸿蒙拒绝新窗口/自动开窗、本地 file/content URL、每实例第三方 Cookie 开启请求；文件选择不支持 capture，事件缺少真实 frame 来源。其他平台差异见 HAR README。
- 公共模型新增 Navigation/HistoryChanged/CapabilityUnsupported，穷尽 when 需更新；旧二进制 ABI 未验证。不能把保留源码入口等同于无条件二进制兼容。
- OHOS Native 编译器生成的 C adapter 有既有 return-type 警告；未关闭类型检查。iOS simulator 的真实 Render 最终链接未执行，已执行 simulator Kotlin compile 和 iphoneos 真实 Render 最终链接。
- 远程结果以不可变标签、GitHub Release、JitPack 实际下载及 ohpm registry 安装为准；本地检查不替代远程验证。

## 0.2.0-rc.2 远程结果

- 不可变标签指向 `2cb91d3a7442dd6d84891749b592315ea65034c7`，GitHub prerelease 已上传，JitPack 17 个模块构建成功。
- `-PremoteOnly` 只从 JitPack 解析本组件：Kuikly Android APK/D8、无 Compose、iOS arm64 Framework/模拟器编译、OHOS shared library 通过；混合 CMP Android APK/D8 与 iOS 模拟器编译也通过。
- iOS 原生代码从同一远程标签下载后，真实 Render Objective-C/最终链接/Swift 类型检查通过。Release HAR 下载与 SHA-256 校验、首航重试/来源白名单/capture 不支持回归和新独立 HAR 消费编译通过。
- ohpm 已接受 `@gycrosskit/webview@0.2.0-rc.2` 的 next 提交，仍在审核，registry 尚不能安装；审核期间使用同标签 Release HAR。设备及生产验收未完成。
- 日志：`build/remote-rc2-consumer.log`、`build/remote-rc2-cmp.log`、`build/remote-rc2-native-ios.log`、`build/remote-rc2-har-consumer.log`；[共用发布记录](https://github.com/gycrosskit/.github/blob/main/docs/发布记录/2026-09-30-WebView与Live预发布.md)。
