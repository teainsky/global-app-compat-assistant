# Global App Compat Assistant v0.1.0

Free MVP / Early Release。

这是一个免费的 Android 设备环境检测与 Google 运行环境诊断工具。首版面向主流 Android 设备提供环境检测和基础诊断；品牌本身不会直接决定兼容方案。

## 当前深度实机验证

- Huawei Pura 70 Pro+
- 型号：HBN-AL80
- 系统：HarmonyOS 4.2

该验证只适用于上述精确设备与系统画像，不自动扩展到其他型号或系统版本。

## 当前范围

- 主流 Android：提供设备环境检测和 Google 环境诊断。
- HarmonyOS 5/6：当前仅提供诊断，不进入旧鸿蒙配置流程。
- 第三方兼容运行环境：当前仅提供诊断，不解锁 microG 自动配置。
- TikTok 不在首版支持范围。

## 安全边界

- 不 Root。
- 不解锁 Bootloader。
- 不修改 ROM。
- 不静默安装；需要安装或升级时必须经过用户确认和 Android 系统安装界面。
- 检测与诊断失败不会阻止 App 的其他本地功能。

这是早期版本，当前只有少量设备完成深度实机验证，不承诺所有 Android 设备或所有 Google 应用均可配置或正常使用。
