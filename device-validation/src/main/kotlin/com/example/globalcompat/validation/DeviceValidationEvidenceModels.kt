package com.example.globalcompat.validation

enum class DeviceValidationEvidenceLevel {
    DEVICE_DETECTED,
    COMPONENT_METADATA_MATCHED,
    FUNCTIONALLY_VALIDATED,
    ARTIFACT_VERIFIED,
    DEVICE_VERIFIED,
}

enum class ValidationEvidenceSource {
    LOCAL_DEVICE_SCAN,
    TRUSTED_CATALOG_COMPARISON,
    USER_CONFIRMATION,
    TRUSTED_HOST_AUDIT,
    USER_SUPPLIED,
    REMOTE_USER_FEEDBACK,
}

data class ValidationDeviceProfile(
    val manufacturer: String?,
    val model: String,
)

data class ValidationSystemProfile(
    val harmonyOsVersion: String?,
    val androidVersion: String,
    val androidApiLevel: Int,
    val romFamily: String,
    val romVersion: String?,
)

data class ValidationComponentEvidence(
    val packageName: String,
    val versionCode: String?,
    val versionName: String?,
    val metadataMatched: Boolean,
    val source: ValidationEvidenceSource,
)

data class ValidationFunctionalEvidence(
    val googleAccountLogin: Boolean,
    val chatGptLoginAndUse: Boolean,
    val chromeGoogleLogin: Boolean,
    val source: ValidationEvidenceSource,
)

data class ValidationArtifactComponentEvidence(
    val packageName: String,
    val sha256: String?,
    val signingCertificateSha256: List<String>,
    val officialArtifactMatched: Boolean,
)

data class ValidationArtifactEvidence(
    val deviceProfile: ValidationDeviceProfile?,
    val systemProfile: ValidationSystemProfile?,
    val components: List<ValidationArtifactComponentEvidence>,
    val source: ValidationEvidenceSource,
)

data class DeviceValidationEvidenceInput(
    val deviceProfile: ValidationDeviceProfile?,
    val systemProfile: ValidationSystemProfile?,
    val componentEvidence: List<ValidationComponentEvidence>,
    val functionalEvidence: ValidationFunctionalEvidence?,
    val artifactEvidence: ValidationArtifactEvidence?,
    val blockedRules: List<String> = emptyList(),
)

data class DeviceValidationEvidenceReport(
    val schemaVersion: Int,
    val deviceProfile: ValidationDeviceProfile?,
    val systemProfile: ValidationSystemProfile?,
    val componentEvidence: List<ValidationComponentEvidence>,
    val functionalEvidence: ValidationFunctionalEvidence?,
    val artifactEvidence: ValidationArtifactEvidence?,
    val attainedLevel: DeviceValidationEvidenceLevel?,
    val missingEvidence: List<String>,
    val blockers: List<String>,
    val evaluatedAt: String,
)
