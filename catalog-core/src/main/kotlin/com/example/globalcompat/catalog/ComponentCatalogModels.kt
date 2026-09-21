package com.example.globalcompat.catalog

import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory

data class ComponentCatalog(
    val schemaVersion: Int,
    val releases: List<ComponentRelease>,
    val verificationPolicy: VerificationPolicy,
    val sourceRecords: List<ArtifactSourceRecord> = emptyList(),
)

data class ComponentRelease(
    val releaseId: String,
    val releaseTag: String,
    val releaseVersion: String,
    val compatibilityStatus: CompatibilityValidationStatus,
    val publishedAt: String,
    val compatibility: CompatibilityConstraint,
    val artifacts: List<ComponentArtifact>,
)

data class ComponentArtifact(
    val componentId: String,
    val packageName: String,
    val releaseVersion: String,
    val artifactFilename: String?,
    val artifactVersionCode: String?,
    val artifactVersionName: String? = null,
    val githubAssetId: Long? = null,
    val variant: ComponentVariant,
    val sourceType: ComponentSourceType,
    val metadataSource: ComponentSourceType?,
    val sourceReleaseUrl: String?,
    val sha256: String?,
    val signingCertificateDigest: String?,
    val minSystemVersion: String,
    val maxSystemVersion: String,
    val verifiedDeviceFamilies: List<String>,
    val blockedDeviceFamilies: List<String>,
    val blockedSystemVersions: List<String>,
    val integrityStatus: ArtifactIntegrityStatus,
    val compatibilityStatus: CompatibilityValidationStatus,
    val publishedAt: String,
    val license: String?,
)

data class CompatibilityConstraint(
    val requiredPlanId: CompatibilityPlanId,
    val requiredDeviceCategory: DeviceCategory,
    val allowedVariants: Set<ComponentVariant>,
    val supportedSystemFamilies: Set<CatalogSystemFamily>,
    val minHarmonyOsMajor: Int,
    val maxHarmonyOsMajor: Int,
)

data class VerificationPolicy(
    val allowedSourceTypes: Set<ComponentSourceType>,
    val recommendableCompatibilityStatuses: Set<CompatibilityValidationStatus>,
    val installableIntegrityStatuses: Set<ArtifactIntegrityStatus>,
    val newReleaseDefaultCompatibilityStatus: CompatibilityValidationStatus,
    val newArtifactDefaultCompatibilityStatus: CompatibilityValidationStatus,
    val requiredVariantByPlan: Map<CompatibilityPlanId, ComponentVariant>,
    val requireSha256ForDownloadVerification: Boolean,
    val requireSigningCertificateForDownloadVerification: Boolean,
)

enum class ComponentSourceType {
    OFFICIAL_MICROG_GITHUB,
    OFFICIAL_MICROG_DOWNLOAD_PAGE,
    OFFICIAL_HUAWEI_APPGALLERY,
}

data class ArtifactDescriptor(
    val componentId: String,
    val packageName: String,
    val releaseVersion: String,
    val artifactFilename: String,
    val artifactVersionCode: String,
    val variant: ComponentVariant,
    val githubAssetId: Long? = null,
    val compatibilityStatus: CompatibilityValidationStatus = CompatibilityValidationStatus.UNTESTED,
)

enum class SourceAvailabilityStatus {
    AVAILABLE,
    MISSING,
    METADATA_ONLY,
    UNRESOLVED,
}

data class ArtifactSourceRecord(
    val componentId: String,
    val sourceType: ComponentSourceType,
    val availabilityStatus: SourceAvailabilityStatus,
    val sourcePageUrl: String,
    val downloadUrl: String? = null,
    val sourceAssetId: Long? = null,
    val observedFilename: String? = null,
    val expectedSize: Long? = null,
    val sourceDigest: String? = null,
    val evidence: List<String> = emptyList(),
    val warningCodes: List<String> = emptyList(),
)

enum class ComponentVariant {
    HUAWEI_HW,
    CUSTOM_ROM,
}

enum class ArtifactIntegrityStatus {
    UNVERIFIED,
    SOURCE_VERIFIED,
    HASH_VERIFIED,
    SIGNATURE_VERIFIED,
    FAILED,
}

enum class InstalledArtifactSignatureStatus {
    ACTUAL_ARTIFACT_MATCH,
    COMPATIBILITY_SIGNATURE_REPORTED,
    SIGNER_MISMATCH,
    UNKNOWN,
}

enum class CompatibilityValidationStatus {
    UNTESTED,
    CANDIDATE,
    DEVICE_VERIFIED,
    BLOCKED,
    DEPRECATED,
}

enum class CatalogSystemFamily {
    HUAWEI_EMUI,
    HUAWEI_HARMONY_OS,
}

enum class ArtifactVerificationReadiness {
    READY_FOR_DOWNLOAD_VERIFICATION,
    NOT_READY_MISSING_SHA256,
    NOT_READY_MISSING_SIGNING_CERTIFICATE,
    NOT_READY_MISSING_INTEGRITY_METADATA,
}

data class ArtifactVerificationAssessment(
    val componentId: String,
    val artifactFilename: String?,
    val integrityStatus: ArtifactIntegrityStatus,
    val compatibilityStatus: CompatibilityValidationStatus,
    val readiness: ArtifactVerificationReadiness,
    val isReadyForDownloadVerification: Boolean,
    val meetsArtifactInstallationGate: Boolean,
    val missingMetadata: List<String>,
)

data class ArtifactIntegrityEvidence(
    val sourceVerified: Boolean = false,
    val sha256: String? = null,
    val sha256Matches: Boolean? = null,
    val signingCertificateDigest: String? = null,
    val signingCertificateMatches: Boolean? = null,
)

data class CatalogMatchRequest(
    val planId: CompatibilityPlanId,
    val deviceCategory: DeviceCategory,
    val deviceFamily: String,
    val systemFamily: CatalogSystemFamily,
    val systemVersion: String,
)

data class CatalogSelection(
    val compatibleReleases: List<ComponentRelease>,
    val compatibleArtifacts: List<ComponentArtifact>,
    val recommendedRelease: ComponentRelease?,
    val recommendedArtifacts: List<ComponentArtifact>,
    val installableArtifacts: List<ComponentArtifact>,
    val verificationAssessments: List<ArtifactVerificationAssessment>,
)
