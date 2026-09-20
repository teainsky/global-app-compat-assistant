package com.example.globalcompat.catalog

import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory

class TrustedComponentCatalogMatcher {
    fun defaultStatusForNewRelease(catalog: ComponentCatalog): ComponentReleaseStatus =
        catalog.verificationPolicy.newReleaseDefaultStatus

    fun select(
        catalog: ComponentCatalog,
        request: CatalogMatchRequest,
    ): CatalogSelection {
        if (request.deviceCategory == DeviceCategory.HARMONYOS_5_PLUS) return EMPTY_SELECTION

        val policy = catalog.verificationPolicy
        val requiredVariant = policy.requiredVariantByPlan[request.planId] ?: return EMPTY_SELECTION
        val compatibleReleases = catalog.releases.filter { release ->
            release.status != ComponentReleaseStatus.BLOCKED &&
                release.status != ComponentReleaseStatus.DEPRECATED &&
                release.compatibility.matches(request, requiredVariant) &&
                release.eligibleArtifacts(policy, requiredVariant, request).hasRequiredPair(request.planId)
        }
        val compatibleArtifacts = compatibleReleases.flatMap { release ->
            release.eligibleArtifacts(policy, requiredVariant, request)
        }
        val recommendedRelease = compatibleReleases
            .filter { release ->
                release.status in policy.recommendableStatuses &&
                    release.eligibleArtifacts(policy, requiredVariant, request).all {
                        it.status in policy.recommendableStatuses
                    }
            }
            .maxWithOrNull(
                compareBy<ComponentRelease> { it.publishedAt }
                    .thenBy { it.releaseVersion },
            )
        val recommendedArtifacts = recommendedRelease
            ?.eligibleArtifacts(policy, requiredVariant, request)
            .orEmpty()

        return CatalogSelection(
            compatibleReleases = compatibleReleases,
            compatibleArtifacts = compatibleArtifacts,
            recommendedRelease = recommendedRelease,
            recommendedArtifacts = recommendedArtifacts,
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
            artifactName = artifact.artifactName,
            readiness = readiness,
            isReadyForDownloadVerification =
                readiness == ArtifactVerificationReadiness.READY_FOR_DOWNLOAD_VERIFICATION,
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

    private fun ComponentRelease.eligibleArtifacts(
        policy: VerificationPolicy,
        requiredVariant: ComponentVariant,
        request: CatalogMatchRequest,
    ): List<ComponentArtifact> = artifacts.filter { artifact ->
        artifact.variant == requiredVariant &&
            artifact.artifactName.endsWith("-hw.apk") &&
            artifact.sourceType in policy.allowedSourceTypes &&
            artifact.status != ComponentReleaseStatus.BLOCKED &&
            artifact.status != ComponentReleaseStatus.DEPRECATED &&
            (artifact.verifiedDeviceFamilies.isEmpty() ||
                request.deviceFamily in artifact.verifiedDeviceFamilies) &&
            request.deviceFamily !in artifact.blockedDeviceFamilies &&
            request.systemVersion !in artifact.blockedSystemVersions
    }

    private fun List<ComponentArtifact>.hasRequiredPair(planId: CompatibilityPlanId): Boolean {
        if (planId != CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN) return isNotEmpty()
        return mapTo(mutableSetOf()) { it.componentId }.containsAll(HUAWEI_REQUIRED_COMPONENT_IDS)
    }

    private companion object {
        val VERSION_NUMBER = Regex("\\d+")
        val HUAWEI_REQUIRED_COMPONENT_IDS = setOf(
            "microg_services_huawei_compatible",
            "microg_companion_huawei_compatible",
        )
        val EMPTY_SELECTION = CatalogSelection(
            compatibleReleases = emptyList(),
            compatibleArtifacts = emptyList(),
            recommendedRelease = null,
            recommendedArtifacts = emptyList(),
            verificationAssessments = emptyList(),
        )
    }
}
