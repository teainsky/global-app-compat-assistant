# Global App Compat Assistant

Android 原生应用，Kotlin + Jetpack Compose。

当前完成 Task 001–002：用户主动点击“开始检测”后，生成结构化的本机设备环境报告，并由独立规则引擎输出 Google 环境设备分类和建议方案。应用只判断和展示方案，不下载或安装 APK；不依赖 GMS 启动，不请求 `QUERY_ALL_PACKAGES`，也不包含支付、登录、官网、自动安装、VPN、代理或 AI API 能力。

首台目标验收设备：Huawei Pura 70 Pro+ / HarmonyOS 4.2。

## 验证

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
git diff --check
```
