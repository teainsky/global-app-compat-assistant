package com.example.globalcompat.data

enum class MarketVariant {
    CHINA_MAINLAND,
    GLOBAL,
    REGIONAL,
    UNKNOWN,
}

enum class PlatformFamily {
    STANDARD_ANDROID,
    HARMONY_ANDROID_COMPAT,
    HARMONY_NATIVE,
    ANDROID_DERIVED,
    UNKNOWN,
}

enum class OsFamily {
    ANDROID,
    HARMONY_OS,
    UNKNOWN,
}

enum class GoogleComponentSetState {
    COMPLETE,
    PARTIAL,
    ABSENT,
    UNKNOWN,
}

enum class ComponentTrust {
    TRUSTED,
    COMPATIBILITY_REPORTED,
    UNVERIFIED,
    MISMATCH,
    UNKNOWN,
}

enum class FunctionalHealth {
    VERIFIED_HEALTHY,
    USER_CONFIRMED,
    UNTESTED,
    FAILED,
    UNKNOWN,
}

enum class PlayCertification {
    VERIFIED,
    NOT_VERIFIED,
    UNKNOWN,
}

data class GoogleEnvironmentAssessment(
    val componentSetState: GoogleComponentSetState,
    val componentTrust: ComponentTrust,
    val functionalHealth: FunctionalHealth,
    val playCertification: PlayCertification,
    val evidence: List<DetectionEvidence>,
)

enum class InstallationCapability {
    USER_CONFIRMED_PACKAGE_INSTALL,
    LEGACY_HARMONY_COMPATIBLE,
    NOT_APPLICABLE,
    UNKNOWN,
}

enum class GlobalValidationLevel {
    DEVICE_VERIFIED,
    ENVIRONMENT_VERIFIED,
    PROBABLE,
    UNKNOWN,
    BLOCKED,
}

data class DeviceProfile(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val deviceFamily: String,
    val marketVariant: MarketVariant,
    val platformFamily: PlatformFamily,
    val osFamily: OsFamily,
    val osVersion: String,
    val androidApiLevel: Int,
    val romFamily: RomFamily,
    val romVersion: String?,
    val googleEnvironmentAssessment: GoogleEnvironmentAssessment,
    val installationCapability: InstallationCapability,
    val validationLevel: GlobalValidationLevel,
    val evidence: List<DetectionEvidence>,
)
