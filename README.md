# Global App Compat Assistant

一个免费、原生 Android 的设备环境检测与 Google 运行环境诊断工具。项目使用 Kotlin + Jetpack Compose，不依赖 GMS 才能启动。

当前阶段：`0.1.0` Free MVP / Early Release。正式 APK 仅使用项目负责人持有的发布密钥签名，并通过 GitHub Release 分发。

## 设备兼容反馈 / 报告问题

[提交设备兼容反馈或报告问题](https://github.com/teainsky/global-app-compat-assistant/issues/new?template=compatibility-report.yml)

未验证设备欢迎反馈，但反馈入口和检测能力不代表该设备已经获得支持或可以自动配置。请勿提交账号信息、设备唯一标识或未脱敏的私人数据。

## 它解决什么问题

- 识别设备、Android/HarmonyOS 分支、ROM 与核心 Google 组件状态。
- 区分“组件存在”“来源可信”“功能可用”和“Play 认证”，避免只看到包名就下结论。
- 用签名 Catalog 和精确设备证据决定是否存在可信配置流程。
- 对证据不足、系统分支不适用或校验失败的场景安全停止。

## 免费版支持范围

免费 MVP 为已识别的主流设备环境提供检测与诊断，并为未知 Android 品牌保留安全的基础 fallback；实际覆盖范围以本表及应用内证据为准。

| 设备/环境 | 检测 | Google 诊断 | 已验证配置 |
| --- | --- | --- | --- |
| Huawei Pura 70 Pro+ / HBN-AL80 / HarmonyOS 4.2 | ✅ | ✅ | ✅ |
| Huawei HarmonyOS 4.x 其他型号 | ✅ | ✅ | ❌ |
| Honor、Xiaomi/Redmi/POCO、OPPO、vivo/iQOO、OnePlus | ✅ | ✅ | ❌ |
| Samsung、Pixel | ✅ | ✅ | 仅在健康证据充分时显示无需处理 |
| Motorola、TECNO/Infinix/itel、Nothing | ✅ | ✅ | ❌ |
| 未知 Android 品牌 | 基础检测 | 基础诊断 | ❌ |
| HarmonyOS 5/6 原生分支 | ✅ | ✅ | 不进入旧鸿蒙流程 |

品牌不是兼容结论。相同品牌在不同系统、市场版本和 Google 环境下可以得到不同结果；市场版本没有可信证据时保持 `UNKNOWN`。

## 当前已验证设备

- Huawei Pura 70 Pro+
- 型号：`HBN-AL80`
- 系统：HarmonyOS `4.2.0`
- Android API：`31`
- 组件版本：microG Huawei `v0.3.16.252432`

验证记录只绑定以上精确设备与系统画像，不自动扩展到 HarmonyOS 4.3、5/6、其他 Pura 或相似 Huawei 型号。

## 使用流程

1. 打开应用，点击“开始检测”。
2. 查看“可检测 / 可诊断 / 已验证可配置”能力标签。
3. 阅读 Google 环境说明并完成三项真实使用确认。
4. 只有精确匹配已签名 `DEVICE_VERIFIED` 记录的设备，才会显示配置入口。
5. 遇到失败时按应用内“常见错误 / 恢复说明”重试或提交 Issue。

## RC2 截图位置

以下位置将在两台目标真机完成 RC2 冒烟后替换为真实运行截图，不使用设计稿或模拟图：

1. 首屏：开始检测与免费版说明（`docs/screenshots/01-start.png`）
2. HBN-AL80：能力标签与当前无需处理（`docs/screenshots/02-hbn-result.png`）
3. 真实使用确认：三项确认与恢复说明（`docs/screenshots/03-self-check.png`）
4. HarmonyOS 6.1 + 卓易通：仅诊断、无配置入口（`docs/screenshots/04-harmony61-diagnostic.png`）

截图内容与隐私处理要求见 [RC2 截图计划](docs/screenshots/README.md)。

## 安全边界

- 不 Root，不解锁 Bootloader，不修改 ROM。
- 不静默安装，不绕过 Android 系统确认，不自动卸载已有组件。
- 不读取 Google 账号内容、IMEI、手机号、Android ID、序列号、MAC、联系人或 SIM 标识。
- 默认完全本地运行，不上传检测结果。
- 不使用第三方 APK 镜像；组件与规则证据失败时 fail closed。
- 未达到精确 `DEVICE_VERIFIED` 的设备不能解锁真实配置执行器。

## 已知限制

- 当前只有一条精确实机验证记录，其他主流 Android 设备以检测和诊断为主。
- “组件完整”不等于可信、功能健康或通过 Play 认证。
- HarmonyOS 5/6 不适用现有 HarmonyOS 1–4 Android 兼容流程。
- OEM 文件权限可能限制本机 APK 原文件审计。
- TikTok 不在首版支持范围。
- 首版不包含账号、支付、广告、会员或后台服务。
- 项目不对未列明应用作可用性承诺；结论只覆盖界面明确显示的检测、诊断和精确验证范围。

## 常见问题

### 为什么我的手机只能诊断，不能配置？

真实配置只对精确设备、系统、可信组件和签名规则均通过验证的画像开放。品牌相同不代表可继承验证结论。

### 检测到 Google 包，为什么没有显示“无需处理”？

包存在只说明组件集合状态。应用还需要可信来源、功能健康或精确设备验证证据，才会给出“无需处理”。

### 应用会修改手机吗？

检测与诊断是只读的。任何未来配置动作也必须经过安全门禁与 Android 系统用户确认。

### HarmonyOS 5/6 可以使用旧鸿蒙方案吗？

不可以。原生 HarmonyOS 和无法确认版本的 Harmony 分支会与 HarmonyOS 1–4 流程隔离。

## 反馈问题

请使用 [设备兼容反馈 / 报告问题](https://github.com/teainsky/global-app-compat-assistant/issues/new?template=compatibility-report.yml)。未验证设备欢迎反馈，但不代表已经支持。不要提交账号信息、设备唯一标识或未脱敏的私人数据。

## 隐私与许可证

- [正式隐私说明](PRIVACY.md)
- [Apache License 2.0](LICENSE)
- [第三方许可证边界](THIRD_PARTY_NOTICES.md)
- [RC2 真机冒烟测试清单](docs/RC2-SMOKE-TEST.md)
- [Release signing 配置](docs/RELEASE_SIGNING.md)

## 开发验证

```powershell
.\.toolchains\gradle-8.9\bin\gradle.bat assembleDebug
.\.toolchains\gradle-8.9\bin\gradle.bat testDebugUnitTest
.\.toolchains\gradle-8.9\bin\gradle.bat lintDebug
git diff --check
```

## 后续工具与项目

这里预留轻量入口，用于未来独立的诊断工具或相关开源项目；首版不在应用内加入商业化导流。
