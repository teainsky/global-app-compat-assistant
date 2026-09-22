package com.example.globalcompat.artifact

import com.example.globalcompat.validation.DeviceValidationEvidenceLevel
import com.example.globalcompat.validation.ValidationArtifactEvidence
import com.example.globalcompat.validation.ValidationDeviceProfile
import com.example.globalcompat.validation.ValidationSystemProfile

enum class OnDeviceArtifactAuditAvailability {
    ON_DEVICE_ARTIFACT_AUDIT_SUPPORTED,
    PARTIALLY_SUPPORTED,
    NOT_ACCESSIBLE,
}

enum class InstalledApkLookupStatus {
    INSTALLED,
    NOT_INSTALLED,
    UNREADABLE,
}

enum class OnDeviceArtifactReadStatus {
    AUDITED,
    NOT_INSTALLED,
    PACKAGE_METADATA_UNREADABLE,
    SOURCE_PATH_UNAVAILABLE,
    APK_FILE_NOT_READABLE,
    SPLIT_APK_LAYOUT_UNSUPPORTED,
    OFFICIAL_METADATA_UNAVAILABLE,
}

data class InstalledApkDescriptor(
    val packageName: String,
    val versionCode: String,
    val versionName: String?,
    val reportedSigningCertificateSha256: List<String>,
    val baseApkPath: String,
    val splitApkPaths: List<String>,
)

data class InstalledApkLookupResult(
    val status: InstalledApkLookupStatus,
    val descriptor: InstalledApkDescriptor?,
)

data class ApkByteDigestResult(
    val readable: Boolean,
    val sha256: String?,
    val failureType: String?,
)

data class OnDeviceArtifactAuditRequest(
    val deviceProfile: ValidationDeviceProfile,
    val systemProfile: ValidationSystemProfile,
)

data class OnDeviceArtifactComponentResult(
    val packageName: String,
    val readStatus: OnDeviceArtifactReadStatus,
    val versionCode: String?,
    val versionName: String?,
    val reportedSigningCertificateSha256: List<String>,
    val splitApkCount: Int,
    val installedApkSha256: String?,
    val officialApkSha256: String?,
    val packageMatched: Boolean,
    val versionMatched: Boolean,
    val signerAccepted: Boolean,
    val officialArtifactMatched: Boolean,
    val failureType: String?,
)

data class OnDeviceArtifactAuditReport(
    val schemaVersion: Int,
    val availability: OnDeviceArtifactAuditAvailability,
    val components: List<OnDeviceArtifactComponentResult>,
    val artifactEvidence: ValidationArtifactEvidence,
    val attainedEvidenceLevel: DeviceValidationEvidenceLevel?,
    val auditedAtEpochMillis: Long,
    val catalogVersion: Long? = null,
    val catalogDigest: String? = null,
)

fun interface InstalledApkPathLookup {
    fun read(packageName: String): InstalledApkLookupResult
}

fun interface ApkByteDigestReader {
    fun readSha256(path: String): ApkByteDigestResult
}
