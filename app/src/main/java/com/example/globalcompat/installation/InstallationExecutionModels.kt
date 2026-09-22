package com.example.globalcompat.installation

import com.example.globalcompat.catalog.ComponentSourceType
import com.example.globalcompat.simulation.SimulatedInstallAction

data class ArtifactDownloadRequest(
    val componentId: String,
    val packageName: String,
    val releaseTag: String,
    val artifactFilename: String,
    val artifactVersionCode: String,
    val artifactVersionName: String,
    val sourceType: ComponentSourceType,
    val sourceUrl: String,
    val sourceAssetId: Long,
    val expectedSha256: String,
    val expectedSigningCertificateSha256: String,
)

data class ArtifactVerificationResult(
    val componentId: String,
    val downloadEvidencePresent: Boolean,
    val sourceAssetId: Long?,
    val artifactFilename: String?,
    val locallyCalculatedSha256: String?,
    val signingCertificateSha256: List<String>,
    val apkSignatureVerificationPassed: Boolean,
    val packageName: String?,
    val versionCode: String?,
    val versionName: String?,
)

enum class InstallationSessionStatus {
    NO_ACTION_REQUIRED,
    BLOCKED,
    READY_FOR_USER_CONFIRMATION,
}

enum class InstallationStepState {
    ALREADY_COMPLETED,
    PENDING_DOWNLOAD,
    DOWNLOADED,
    VERIFIED,
    READY_FOR_USER_CONFIRMATION,
    BLOCKED,
}

enum class InstallationBlockReason {
    DEVICE_BRANCH_NOT_ALLOWED,
    HARMONYOS_5_PLUS_NOT_SUPPORTED,
    SIMULATION_PLAN_BLOCKED,
    OFFICIAL_SOURCE_UNAVAILABLE,
    CATALOG_METADATA_INCOMPLETE,
    SHA256_NOT_AUDITED,
    SIGNATURE_NOT_AUDITED,
    ARTIFACT_INTEGRITY_NOT_SIGNATURE_VERIFIED,
    COMPATIBILITY_NOT_DEVICE_VERIFIED,
    DECISION_NOT_VERIFIED_WORKFLOW,
    DOWNLOAD_EVIDENCE_MISSING,
    DOWNLOADED_ARTIFACT_SOURCE_MISMATCH,
    SHA256_MISMATCH,
    SIGNATURE_MISMATCH,
    PACKAGE_NAME_MISMATCH,
    VERSION_MISMATCH,
    VERSION_CONFLICT_REQUIRES_RESOLUTION,
    CURRENT_ARTIFACT_NOT_VERIFIED,
}

data class InstallationSessionStep(
    val componentId: String,
    val packageName: String,
    val action: SimulatedInstallAction?,
    val order: Int?,
    val state: InstallationStepState,
    val downloadRequest: ArtifactDownloadRequest?,
    val verificationResult: ArtifactVerificationResult?,
    val blockReasons: List<InstallationBlockReason>,
)

data class InstallationSessionPlan(
    val schemaVersion: Int,
    val status: InstallationSessionStatus,
    val executionAllowed: Boolean,
    val userConfirmationRequired: Boolean,
    val userMessage: String,
    val steps: List<InstallationSessionStep>,
    val blockReasons: List<InstallationBlockReason>,
)
