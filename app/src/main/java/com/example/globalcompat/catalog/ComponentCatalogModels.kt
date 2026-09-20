package com.example.globalcompat.catalog

import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory

data class ComponentCatalog(
    val schemaVersion: Int,
    val releases: List<ComponentRelease>,
    val verificationPolicy: VerificationPolicy,
)

data class ComponentRelease(
    val releaseId: String,
    val releaseVersion: String,
    val status: ComponentReleaseStatus,
    val publishedAt: String,
    val compatibility: CompatibilityConstraint,
    val artifacts: List<ComponentArtifact>,
)

data class ComponentArtifact(
    val componentId: String,
    val packageName: String,
    val releaseVersion: String,
    val artifactName: String,
    val variant: ComponentVariant,
    val sourceType: ComponentSourceType,
    val officialSource: String,
    val sha256: String?,
    val signingCertificateDigest: String?,
    val minSystemVersion: String,
    val maxSystemVersion: String,
    val verifiedDeviceFamilies: List<String>,
    val blockedDeviceFamilies: List<String>,
    val blockedSystemVersions: List<String>,
    val status: ComponentReleaseStatus,
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
    val recommendableStatuses: Set<ComponentReleaseStatus>,
    val newReleaseDefaultStatus: ComponentReleaseStatus,
    val requiredVariantByPlan: Map<CompatibilityPlanId, ComponentVariant>,
    val requireSha256ForDownloadVerification: Boolean,
    val requireSigningCertificateForDownloadVerification: Boolean,
)

enum class ComponentSourceType {
    OFFICIAL_MICROG_GITHUB,
    OFFICIAL_HUAWEI_APPGALLERY,
}

enum class ComponentVariant {
    HUAWEI_HW,
    CUSTOM_ROM,
}

enum class ComponentReleaseStatus {
    VERIFIED,
    CANDIDATE,
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
    val artifactName: String,
    val readiness: ArtifactVerificationReadiness,
    val isReadyForDownloadVerification: Boolean,
    val missingMetadata: List<String>,
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
    val verificationAssessments: List<ArtifactVerificationAssessment>,
)
