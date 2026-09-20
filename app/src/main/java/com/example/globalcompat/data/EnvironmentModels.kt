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
    HARMONY_OS,
    HARMONY_OS_5_PLUS,
    HYPER_OS,
    COLOR_OS,
    ORIGIN_OS,
    MAGIC_OS,
    ONE_UI,
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
    val compatibilityPlan: CompatibilityPlan,
)
