package com.example.globalcompat.data

data class DeviceIdentity(
    val brand: String,
    val manufacturer: String,
    val model: String,
    val product: String,
    val device: String,
    val hardware: String,
    val board: String,
    val supportedAbis: List<String>,
)
data class AndroidPlatform(
    val apiLevel: Int,
    val release: String,
    val securityPatch: String?,
    val buildDisplay: String,
    val buildIncremental: String,
    val fingerprint: String,
)

enum class RomFamily {
    AOSP,
    PIXEL_ANDROID,
    ONE_UI,
    HYPER_OS,
    COLOR_OS,
    OXYGEN_OS,
    ORIGIN_OS,
    FUNTOUCH_OS,
    MAGIC_OS,
    HARMONY_OS,
    HARMONY_OS_5_PLUS,
    MY_OS,
    REALME_UI,
    NOTHING_OS,
    MOTOROLA_ANDROID,
    ASUS_ANDROID,
    SONY_ANDROID,
    TECNO_HIOS,
    INFINIX_XOS,
    ITEL_OS,
    TCL_UI,
    HMD_ANDROID,
    UNKNOWN,
}

enum class DetectionConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN,
}

data class DetectionEvidence(
    val key: String,
    val value: String,
)

data class RomIdentification(
    val family: RomFamily,
    val displayName: String,
    val version: String?,
    val confidence: DetectionConfidence,
    val evidence: List<DetectionEvidence>,
)

enum class ComponentId {
    GOOGLE_PLAY_SERVICES,
    GOOGLE_PLAY_STORE,
    HMS_CORE,
}

enum class ComponentPresence {
    PRESENT,
    NOT_INSTALLED,
    CHECK_FAILED,
}

data class SystemComponent(
    val id: ComponentId,
    val displayName: String,
    val packageName: String,
    val presence: ComponentPresence,
    val enabled: Boolean?,
    val versionName: String?,
    val versionCode: Long?,
)

enum class CompatibilityLayerAssessment {
    NOT_ASSESSED,
}

data class GoogleCompatibilityLayerStatus(
    val assessment: CompatibilityLayerAssessment,
    val note: String,
)

data class EnvironmentReport(
    val schemaVersion: Int,
    val scannedAtEpochMillis: Long,
    val device: DeviceIdentity,
    val android: AndroidPlatform,
    val rom: RomIdentification,
    val components: List<SystemComponent>,
    val googleCompatibilityLayer: GoogleCompatibilityLayerStatus,
    val deviceProfile: DeviceProfile,
    val compatibilityPlan: CompatibilityPlan,
)
