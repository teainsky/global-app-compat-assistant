package com.example.globalcompat.data

enum class DeviceCategory {
    STANDARD_GMS,
    HUAWEI_HARMONY_ANDROID_COMPAT,
    CHINA_ANDROID_NO_GMS,
    PARTIAL_GMS,
    HARMONYOS_5_PLUS,
    HARMONY_VERSION_UNKNOWN,
    UNKNOWN,
}

enum class CompatibilityPlanId {
    NO_ACTION_REQUIRED,
    HUAWEI_MICROG_COMPAT_PLAN,
    GMS_REPAIR_REQUIRED,
    UNSUPPORTED_OR_UNKNOWN,
}
