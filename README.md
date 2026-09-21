# Global App Compat Assistant

Android 原生应用，Kotlin + Jetpack Compose。

当前已完成设备环境扫描、方案匹配、可信组件 catalog、开发侧官方组件审计，以及用户主动触发的真实设备基线采集。APK 可只读获取已安装 Google 两件套的版本与签名摘要，和已审计官方元数据比较，并通过系统文件选择器导出隐私安全的 `device-baseline.json`。版本与签名匹配不会自动提升全局设备兼容状态。

报告页包含“模拟安装计划”，以纯规则方式展示设备检测、方案匹配、官方组件选择、完整性证据、当前组件判断、依赖顺序和下一步动作。该流程不会联网下载或执行安装；`CANDIDATE/UNTESTED` 组件仍不具备真实安装资格。

安装执行安全门禁会把模拟步骤转换成结构化安装会话，并逐项核对设备分支、官方来源、SHA-256、签名、包名、版本、artifact 完整性状态与设备兼容验证状态。当前 Huawei catalog 仍为 `CANDIDATE/UNTESTED`，因此安装入口保持禁用；项目只预留 Android 系统用户确认端口，不包含下载器或 `PackageInstaller` 实现。

应用不下载或安装 APK，不依赖 GMS 启动，不请求 `QUERY_ALL_PACKAGES`，不读取账号内容或设备唯一标识，不上传报告，也不包含支付、登录操作、官网、自动安装、VPN、代理或 AI API 能力。

首台目标验收设备：Huawei Pura 70 Pro+ / HarmonyOS 4.2。

## 验证

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
git diff --check
```
