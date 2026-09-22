package com.example.globalcompat.catalog

import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory

class TrustedComponentCatalogMatcher {
    fun defaultStatusForNewRelease(catalog: ComponentCatalog): CompatibilityValidationStatus =
        catalog.verificationPolicy.newReleaseDefaultCompatibilityStatus

    fun defaultStatusForNewArtifact(catalog: ComponentCatalog): CompatibilityValidationStatus =
        catalog.verificationPolicy.newArtifactDefaultCompatibilityStatus

    fun applyIntegrityEvidence(
        artifact: ComponentArtifact,
        evidence: ArtifactIntegrityEvidence,
    ): ComponentArtifact {
        val hasSha256 = evidence.sha256 != null || artifact.sha256 != null
        val hasSigningCertificate =
            evidence.signingCertificateDigest != null || artifact.signingCertificateDigest != null
        val integrityStatus = when {
            evidence.sha256Matches == false || evidence.signingCertificateMatches == false ->
                ArtifactIntegrityStatus.FAILED
            evidence.sha256Matches == true &&
                evidence.signingCertificateMatches == true &&
                hasSha256 &&
                hasSigningCertificate ->
                ArtifactIntegrityStatus.SIGNATURE_VERIFIED
            evidence.sha256Matches == true && hasSha256 -> ArtifactIntegrityStatus.HASH_VERIFIED
            evidence.sourceVerified -> ArtifactIntegrityStatus.SOURCE_VERIFIED
            else -> ArtifactIntegrityStatus.UNVERIFIED
        }
        return artifact.copy(
            sha256 = evidence.sha256 ?: artifact.sha256,
            signingCertificateDigest =
                evidence.signingCertificateDigest ?: artifact.signingCertificateDigest,
            integrityStatus = integrityStatus,
        )
    }

    fun select(
        catalog: ComponentCatalog,
        request: CatalogMatchRequest,
    ): CatalogSelection {
        if (request.deviceCategory == DeviceCategory.HARMONYOS_5_PLUS) return EMPTY_SELECTION

        val policy = catalog.verificationPolicy
        val requiredVariant = policy.requiredVariantByPlan[request.planId] ?: return EMPTY_SELECTION
        val compatibleReleases = catalog.releases.map { release ->
            release.withExactDeviceVerification(catalog, request)
        }.filter { release ->
            release.compatibilityStatus != CompatibilityValidationStatus.BLOCKED &&
                release.compatibilityStatus != CompatibilityValidationStatus.DEPRECATED &&
                release.compatibility.matches(request, requiredVariant) &&
                release.eligibleArtifacts(catalog, policy, requiredVariant, request)
                    .hasRequiredPair(request.planId)
        }
        val compatibleArtifacts = compatibleReleases.flatMap { release ->
            release.eligibleArtifacts(catalog, policy, requiredVariant, request)
        }
        val recommendedRelease = compatibleReleases
            .filter { release ->
                release.compatibilityStatus in policy.recommendableCompatibilityStatuses &&
                    release.eligibleArtifacts(catalog, policy, requiredVariant, request).all {
                        it.compatibilityStatus in policy.recommendableCompatibilityStatuses
                    }
            }
            .maxWithOrNull(
                compareBy<ComponentRelease> { it.publishedAt }
                    .thenBy { it.releaseTag },
            )
        val recommendedArtifacts = recommendedRelease
            ?.eligibleArtifacts(catalog, policy, requiredVariant, request)
            .orEmpty()
        val installableArtifacts = recommendedArtifacts.takeIf { artifacts ->
            artifacts.isNotEmpty() && artifacts.all { artifact ->
                artifact.integrityStatus in policy.installableIntegrityStatuses &&
                    artifact.compatibilityStatus == CompatibilityValidationStatus.DEVICE_VERIFIED &&
                    artifact.hasRequiredIntegrityMetadata(policy)
            }
        }.orEmpty()

        return CatalogSelection(
            compatibleReleases = compatibleReleases,
            compatibleArtifacts = compatibleArtifacts,
            recommendedRelease = recommendedRelease,
            recommendedArtifacts = recommendedArtifacts,
            installableArtifacts = installableArtifacts,
            verificationAssessments = compatibleArtifacts.map { artifact ->
                assessVerification(policy, artifact)
            },
        )
    }

    fun assessVerification(
        policy: VerificationPolicy,
        artifact: ComponentArtifact,
    ): ArtifactVerificationAssessment {
        val missingMetadata = buildList {
            if (policy.requireSha256ForDownloadVerification && artifact.sha256 == null) {
                add("sha256")
            }
            if (
                policy.requireSigningCertificateForDownloadVerification &&
                artifact.signingCertificateDigest == null
            ) {
                add("signingCertificateDigest")
            }
        }
        val readiness = when (missingMetadata.toSet()) {
            emptySet<String>() -> ArtifactVerificationReadiness.READY_FOR_DOWNLOAD_VERIFICATION
            setOf("sha256") -> ArtifactVerificationReadiness.NOT_READY_MISSING_SHA256
            setOf("signingCertificateDigest") ->
                ArtifactVerificationReadiness.NOT_READY_MISSING_SIGNING_CERTIFICATE
            else -> ArtifactVerificationReadiness.NOT_READY_MISSING_INTEGRITY_METADATA
        }
        return ArtifactVerificationAssessment(
            componentId = artifact.componentId,
            artifactFilename = artifact.artifactFilename,
            integrityStatus = artifact.integrityStatus,
            compatibilityStatus = artifact.compatibilityStatus,
            readiness = readiness,
            isReadyForDownloadVerification =
                readiness == ArtifactVerificationReadiness.READY_FOR_DOWNLOAD_VERIFICATION,
            meetsArtifactInstallationGate =
                artifact.integrityStatus in policy.installableIntegrityStatuses &&
                    artifact.compatibilityStatus == CompatibilityValidationStatus.DEVICE_VERIFIED &&
                    artifact.hasRequiredIntegrityMetadata(policy),
            missingMetadata = missingMetadata,
        )
    }

    private fun CompatibilityConstraint.matches(
        request: CatalogMatchRequest,
        requiredVariant: ComponentVariant,
    ): Boolean {
        if (requiredPlanId != request.planId) return false
        if (requiredDeviceCategory != request.deviceCategory) return false
        if (requiredVariant !in allowedVariants) return false
        if (request.systemFamily !in supportedSystemFamilies) return false
        if (request.systemFamily == CatalogSystemFamily.HUAWEI_EMUI) return true

        val harmonyMajor = VERSION_NUMBER.find(request.systemVersion)?.value?.toIntOrNull()
            ?: return false
        return harmonyMajor in minHarmonyOsMajor..maxHarmonyOsMajor
    }

    private fun ComponentRelease.withExactDeviceVerification(
        catalog: ComponentCatalog,
        request: CatalogMatchRequest,
    ): ComponentRelease {
        val exactRecord = catalog.verifiedDeviceRecords.singleOrNull { record ->
            record.compatibilityStatus == CompatibilityValidationStatus.DEVICE_VERIFIED &&
                record.deviceModel == request.deviceFamily &&
                record.deviceFamily == request.deviceFamily &&
                record.romFamily == HARMONY_OS_ROM_FAMILY &&
                request.systemFamily == CatalogSystemFamily.HUAWEI_HARMONY_OS &&
                record.harmonyOsVersion == request.systemVersion &&
                record.androidApiLevel == request.androidApiLevel &&
                record.componentRelease == releaseTag
        } ?: return this
        return copy(
            compatibilityStatus = CompatibilityValidationStatus.DEVICE_VERIFIED,
            artifacts = artifacts.map { artifact ->
                artifact.copy(
                    compatibilityStatus = CompatibilityValidationStatus.DEVICE_VERIFIED,
                    verifiedDeviceFamilies = listOf(exactRecord.deviceModel),
                    verifiedSystemVersions = listOf(exactRecord.harmonyOsVersion),
                )
            },
        )
    }

    private fun ComponentRelease.eligibleArtifacts(
        catalog: ComponentCatalog,
        policy: VerificationPolicy,
        requiredVariant: ComponentVariant,
        request: CatalogMatchRequest,
    ): List<ComponentArtifact> = artifacts.filter { artifact ->
        hasExplicitMetadataFor(artifact) &&
            artifact.variant == requiredVariant &&
            artifact.artifactFilename?.endsWith("-hw.apk") == true &&
            artifact.sourceType in policy.allowedSourceTypes &&
            artifact.integrityStatus != ArtifactIntegrityStatus.FAILED &&
            artifact.compatibilityStatus != CompatibilityValidationStatus.BLOCKED &&
            artifact.compatibilityStatus != CompatibilityValidationStatus.DEPRECATED &&
            !catalog.isBlocked(artifact) &&
            (artifact.verifiedDeviceFamilies.isEmpty() ||
                request.deviceFamily in artifact.verifiedDeviceFamilies) &&
            (artifact.verifiedSystemVersions.isEmpty() ||
                request.systemVersion in artifact.verifiedSystemVersions) &&
            request.deviceFamily !in artifact.blockedDeviceFamilies &&
            request.systemVersion !in artifact.blockedSystemVersions
    }

    private fun ComponentCatalog.isBlocked(artifact: ComponentArtifact): Boolean =
        blockedVersions.any { rule ->
            rule.componentId == artifact.componentId &&
                (artifact.releaseVersion in rule.versions ||
                    artifact.artifactVersionCode in rule.versions ||
                    artifact.artifactVersionName in rule.versions)
        }

    private fun ComponentRelease.hasExplicitMetadataFor(artifact: ComponentArtifact): Boolean =
        releaseTag.isNotBlank() &&
            releaseVersion.isNotBlank() &&
            artifact.releaseVersion == releaseVersion &&
            !artifact.artifactFilename.isNullOrBlank() &&
            !artifact.artifactVersionCode.isNullOrBlank() &&
            !artifact.artifactVersionName.isNullOrBlank() &&
            artifact.metadataSource != null &&
            artifact.metadataSource == artifact.sourceType &&
            !artifact.sourceReleaseUrl.isNullOrBlank() &&
            (artifact.sourceType != ComponentSourceType.OFFICIAL_MICROG_GITHUB ||
                artifact.githubAssetId != null)

    private fun List<ComponentArtifact>.hasRequiredPair(planId: CompatibilityPlanId): Boolean {
        if (planId != CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN) return isNotEmpty()
        return mapTo(mutableSetOf()) { it.componentId }.containsAll(HUAWEI_REQUIRED_COMPONENT_IDS)
    }

    private fun ComponentArtifact.hasRequiredIntegrityMetadata(policy: VerificationPolicy): Boolean =
        (!policy.requireSha256ForDownloadVerification || sha256 != null) &&
            (!policy.requireSigningCertificateForDownloadVerification ||
                signingCertificateDigest != null)

    private companion object {
        val VERSION_NUMBER = Regex("\\d+")
        val HUAWEI_REQUIRED_COMPONENT_IDS = setOf(
            "microg_services_huawei_compatible",
            "microg_companion_huawei_compatible",
        )
        const val HARMONY_OS_ROM_FAMILY = "HARMONY_OS"
        val EMPTY_SELECTION = CatalogSelection(
            compatibleReleases = emptyList(),
            compatibleArtifacts = emptyList(),
            recommendedRelease = null,
            recommendedArtifacts = emptyList(),
            installableArtifacts = emptyList(),
            verificationAssessments = emptyList(),
        )
    }
}
