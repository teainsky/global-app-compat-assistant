# Global App Compat Assistant

Android 原生应用，Kotlin + Jetpack Compose。

当前已完成设备环境扫描、方案匹配、可信组件 catalog、开发侧官方组件审计，以及用户主动触发的真实设备基线采集。APK 可只读获取已安装 Google 两件套的版本与签名摘要，和已审计官方元数据比较，并通过系统文件选择器导出隐私安全的 `device-baseline.json`。版本与签名匹配不会自动提升全局设备兼容状态。

应用不下载或安装 APK，不依赖 GMS 启动，不请求 `QUERY_ALL_PACKAGES`，不读取账号内容或设备唯一标识，不上传报告，也不包含支付、登录操作、官网、自动安装、VPN、代理或 AI API 能力。

首台目标验收设备：Huawei Pura 70 Pro+ / HarmonyOS 4.2。

## 验证

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
git diff --check
```
