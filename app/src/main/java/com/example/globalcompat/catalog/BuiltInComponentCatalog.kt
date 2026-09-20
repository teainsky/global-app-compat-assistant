package com.example.globalcompat.catalog

import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory

object BuiltInComponentCatalog {
    private const val RELEASE_VERSION = "v0.3.16.252432"
    private const val PUBLISHED_AT = "2026-07-14T14:11:55Z"
    private const val RELEASE_SOURCE =
        "https://github.com/microg/GmsCore/releases/tag/v0.3.16.252432"

    val catalog = ComponentCatalog(
        schemaVersion = 1,
        releases = listOf(
            ComponentRelease(
                releaseId = "microg-huawei-hw-v0.3.16.252432",
                releaseVersion = RELEASE_VERSION,
                compatibilityStatus = CompatibilityValidationStatus.CANDIDATE,
                publishedAt = PUBLISHED_AT,
                compatibility = CompatibilityConstraint(
                    requiredPlanId = CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN,
                    requiredDeviceCategory = DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT,
                    allowedVariants = setOf(ComponentVariant.HUAWEI_HW),
                    supportedSystemFamilies = setOf(
                        CatalogSystemFamily.HUAWEI_EMUI,
                        CatalogSystemFamily.HUAWEI_HARMONY_OS,
                    ),
                    minHarmonyOsMajor = 1,
                    maxHarmonyOsMajor = 4,
                ),
                artifacts = listOf(
                    huaweiArtifact(
                        componentId = "microg_services_huawei_compatible",
                        packageName = "com.google.android.gms",
                        artifactName = "com.google.android.gms-252432032-hw.apk",
                        license = "Apache-2.0",
                    ),
                    huaweiArtifact(
                        componentId = "microg_companion_huawei_compatible",
                        packageName = "com.android.vending",
                        artifactName = "com.android.vending-84022632-hw.apk",
                        license = null,
                    ),
                ),
            ),
        ),
        verificationPolicy = VerificationPolicy(
            allowedSourceTypes = setOf(
                ComponentSourceType.OFFICIAL_MICROG_GITHUB,
                ComponentSourceType.OFFICIAL_HUAWEI_APPGALLERY,
            ),
            recommendableCompatibilityStatuses = setOf(
                CompatibilityValidationStatus.DEVICE_VERIFIED,
            ),
            installableIntegrityStatuses = setOf(ArtifactIntegrityStatus.SIGNATURE_VERIFIED),
            newReleaseDefaultCompatibilityStatus = CompatibilityValidationStatus.CANDIDATE,
            newArtifactDefaultCompatibilityStatus = CompatibilityValidationStatus.UNTESTED,
            requiredVariantByPlan = mapOf(
                CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN to ComponentVariant.HUAWEI_HW,
            ),
            requireSha256ForDownloadVerification = true,
            requireSigningCertificateForDownloadVerification = true,
        ),
    )

    private fun huaweiArtifact(
        componentId: String,
        packageName: String,
        artifactName: String,
        license: String?,
    ) = ComponentArtifact(
        componentId = componentId,
        packageName = packageName,
        releaseVersion = RELEASE_VERSION,
        artifactName = artifactName,
        variant = ComponentVariant.HUAWEI_HW,
        sourceType = ComponentSourceType.OFFICIAL_MICROG_GITHUB,
        officialSource = RELEASE_SOURCE,
        sha256 = null,
        signingCertificateDigest = null,
        minSystemVersion = "HarmonyOS 1.0 / supported EMUI",
        maxSystemVersion = "HarmonyOS 4.x / supported EMUI",
        verifiedDeviceFamilies = emptyList(),
        blockedDeviceFamilies = emptyList(),
        blockedSystemVersions = emptyList(),
        integrityStatus = ArtifactIntegrityStatus.SOURCE_VERIFIED,
        compatibilityStatus = CompatibilityValidationStatus.UNTESTED,
        publishedAt = PUBLISHED_AT,
        license = license,
    )
}
