package com.example.globalcompat.validation

data class VerifiedDeviceCompatibilityRecord(
    val schemaVersion: Int,
    val deviceModel: String,
    val deviceFamily: String,
    val romFamily: String,
    val harmonyOsVersion: String,
    val androidApiLevel: Int,
    val componentRelease: String,
    val componentVersionCodes: Map<String, String>,
    val componentSignerDigests: Map<String, List<String>>,
    val validationDate: String,
    val evidenceDigest: String,
    val compatibilityStatus: PublishedCompatibilityStatus,
)

enum class PublishedCompatibilityStatus {
    DEVICE_VERIFIED,
}

enum class DeviceRecordPublicationRejection {
    EVIDENCE_DIGEST_INVALID,
    EVIDENCE_DIGEST_MISMATCH,
    EVIDENCE_SCHEMA_UNSUPPORTED,
    EVIDENCE_LEVEL_NOT_DEVICE_VERIFIED,
    EVIDENCE_INCOMPLETE,
    ARTIFACT_EVIDENCE_REQUIRED,
    ARTIFACT_MISMATCH,
    EXACT_PROFILE_MISMATCH,
    CATALOG_RELEASE_NOT_FOUND,
    BLOCKED_VERSION,
    HARMONYOS_5_PLUS_NOT_PUBLISHABLE,
}

sealed interface DeviceRecordPublicationResult {
    data class Approved(
        val record: VerifiedDeviceCompatibilityRecord,
    ) : DeviceRecordPublicationResult

    data class Rejected(
        val reasons: List<DeviceRecordPublicationRejection>,
    ) : DeviceRecordPublicationResult
}
