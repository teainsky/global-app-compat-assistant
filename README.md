# Global App Compat Assistant

Android 原生应用，Kotlin + Jetpack Compose。

当前完成 Task 001–002.5，并新增独立 Trusted Component Catalog：用户主动检测设备后，由规则引擎输出 Google 环境分类与方案；目录仅记录官方组件来源、版本状态、适用设备和校验策略。应用不下载或安装 APK，不依赖 GMS 启动，不请求 `QUERY_ALL_PACKAGES`，也不包含支付、登录、官网、自动安装、VPN、代理或 AI API 能力。

首台目标验收设备：Huawei Pura 70 Pro+ / HarmonyOS 4.2。

## 验证

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
git diff --check
```
