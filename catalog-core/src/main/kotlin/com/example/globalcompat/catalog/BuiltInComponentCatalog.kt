package com.example.globalcompat.catalog

import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory

object BuiltInComponentCatalog {
    private const val RELEASE_TAG = "v0.3.16.252432"
    private const val RELEASE_VERSION = "0.3.16.252432"
    private const val PUBLISHED_AT = "2026-07-14T14:11:55Z"
    private const val RELEASE_SOURCE =
        "https://github.com/microg/GmsCore/releases/tag/v0.3.16.252432"

    val catalog = ComponentCatalog(
        schemaVersion = 4,
        releases = listOf(
            ComponentRelease(
                releaseId = "microg-huawei-hw-v0.3.16.252432",
                releaseTag = RELEASE_TAG,
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
                        artifactFilename = "com.google.android.gms-252432032-hw.apk",
                        artifactVersionCode = "252432032",
                        artifactVersionName = "0.3.16.252432-hw",
                        githubAssetId = 476760666L,
                        sha256 = "a44ce933e2336d3340eb82ad3bb28bba03bc56a7b3cf3c98250a225c55b572de",
                        license = "Apache-2.0",
                    ),
                    huaweiArtifact(
                        componentId = "microg_companion_huawei_compatible",
                        packageName = "com.android.vending",
                        artifactFilename = "com.android.vending-84022632-hw.apk",
                        artifactVersionCode = "84022632",
                        artifactVersionName = "0.3.16.40226-hw",
                        githubAssetId = 476761461L,
                        sha256 = "c1aa0c8854fcdac31d23d54e1ea62daedff6b7a6405a2f5ff5351c2dde8f113d",
                        license = null,
                    ),
                ),
            ),
        ),
        verificationPolicy = VerificationPolicy(
            allowedSourceTypes = setOf(
                ComponentSourceType.OFFICIAL_MICROG_GITHUB,
                ComponentSourceType.OFFICIAL_MICROG_DOWNLOAD_PAGE,
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
        artifactFilename: String,
        artifactVersionCode: String,
        artifactVersionName: String,
        githubAssetId: Long,
        sha256: String,
        license: String?,
    ) = ComponentArtifact(
        componentId = componentId,
        packageName = packageName,
        releaseVersion = RELEASE_VERSION,
        artifactFilename = artifactFilename,
        artifactVersionCode = artifactVersionCode,
        artifactVersionName = artifactVersionName,
        githubAssetId = githubAssetId,
        variant = ComponentVariant.HUAWEI_HW,
        sourceType = ComponentSourceType.OFFICIAL_MICROG_GITHUB,
        metadataSource = ComponentSourceType.OFFICIAL_MICROG_GITHUB,
        sourceReleaseUrl = RELEASE_SOURCE,
        sha256 = sha256,
        signingCertificateDigest = SIGNER_SHA256,
        minSystemVersion = "HarmonyOS 1.0 / supported EMUI",
        maxSystemVersion = "HarmonyOS 4.x / supported EMUI",
        verifiedDeviceFamilies = emptyList(),
        blockedDeviceFamilies = emptyList(),
        blockedSystemVersions = emptyList(),
        integrityStatus = ArtifactIntegrityStatus.SIGNATURE_VERIFIED,
        compatibilityStatus = CompatibilityValidationStatus.UNTESTED,
        publishedAt = PUBLISHED_AT,
        license = license,
    )

    private const val SIGNER_SHA256 =
        "9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165"
}
