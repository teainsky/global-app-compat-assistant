package com.example.globalcompat.validation

class DeviceValidationPromotionPolicy {
    fun evaluate(
        input: DeviceValidationEvidenceInput,
        evaluatedAt: String,
    ): DeviceValidationEvidenceReport {
        val missing = mutableListOf<String>()
        val blockers = input.blockedRules.toMutableList()
        val deviceDetected = input.deviceProfile.isDetected() && input.systemProfile.isDetected()
        if (!input.deviceProfile.isDetected()) missing += MISSING_DEVICE_PROFILE
        if (!input.systemProfile.isDetected()) missing += MISSING_SYSTEM_PROFILE

        if (input.systemProfile.isHarmonyOs5Plus()) {
            blockers += BLOCKER_HARMONY_OS_5_PLUS
        }

        val componentsByPackage = input.componentEvidence.associateBy { it.packageName }
        val metadataMatched = REQUIRED_PACKAGES.all { packageName ->
            componentsByPackage[packageName]?.let { evidence ->
                evidence.metadataMatched &&
                    evidence.source == ValidationEvidenceSource.TRUSTED_CATALOG_COMPARISON
            } == true
        }
        REQUIRED_PACKAGES.filterNot { packageName ->
            componentsByPackage[packageName]?.let { evidence ->
                evidence.metadataMatched &&
                    evidence.source == ValidationEvidenceSource.TRUSTED_CATALOG_COMPARISON
            } == true
        }.forEach { missing += "$MISSING_COMPONENT_METADATA:$it" }

        val functionalValidated = input.functionalEvidence?.let { evidence ->
            evidence.source == ValidationEvidenceSource.USER_CONFIRMATION &&
                evidence.googleAccountLogin &&
                evidence.chatGptLoginAndUse &&
                evidence.chromeGoogleLogin
        } == true
        if (!functionalValidated) missing += MISSING_FUNCTIONAL_VALIDATION

        val artifactEvidence = input.artifactEvidence
        val trustedArtifactSource =
            artifactEvidence?.source == ValidationEvidenceSource.TRUSTED_HOST_AUDIT
        if (!trustedArtifactSource) missing += MISSING_TRUSTED_ARTIFACT_AUDIT

        val artifactProfilesMatch = artifactEvidence != null &&
            artifactEvidence.deviceProfile.exactlyMatches(input.deviceProfile) &&
            artifactEvidence.systemProfile == input.systemProfile
        if (artifactEvidence != null && !artifactProfilesMatch) {
            blockers += BLOCKER_ARTIFACT_PROFILE_MISMATCH
            missing += MISSING_ARTIFACT_PROFILE_BINDING
        }

        val artifactsByPackage = artifactEvidence?.components?.associateBy { it.packageName }.orEmpty()
        val artifactsMatched = REQUIRED_PACKAGES.all { packageName ->
            artifactsByPackage[packageName]?.officialArtifactMatched == true
        }
        REQUIRED_PACKAGES.filterNot { artifactsByPackage[it]?.officialArtifactMatched == true }
            .forEach { missing += "$MISSING_ARTIFACT_MATCH:$it" }
        if (artifactEvidence?.components?.any { !it.officialArtifactMatched } == true) {
            blockers += BLOCKER_ARTIFACT_MISMATCH
        }

        val artifactVerified = trustedArtifactSource && artifactProfilesMatch && artifactsMatched
        val attainedLevel = when {
            !deviceDetected -> null
            !metadataMatched -> DeviceValidationEvidenceLevel.DEVICE_DETECTED
            !functionalValidated -> DeviceValidationEvidenceLevel.COMPONENT_METADATA_MATCHED
            !artifactVerified -> DeviceValidationEvidenceLevel.FUNCTIONALLY_VALIDATED
            blockers.isNotEmpty() -> DeviceValidationEvidenceLevel.ARTIFACT_VERIFIED
            else -> DeviceValidationEvidenceLevel.DEVICE_VERIFIED
        }
        return DeviceValidationEvidenceReport(
            schemaVersion = SCHEMA_VERSION,
            deviceProfile = input.deviceProfile,
            systemProfile = input.systemProfile,
            componentEvidence = input.componentEvidence,
            functionalEvidence = input.functionalEvidence,
            artifactEvidence = input.artifactEvidence,
            attainedLevel = attainedLevel,
            missingEvidence = missing.distinct(),
            blockers = blockers.distinct(),
            evaluatedAt = evaluatedAt,
        )
    }

    private fun ValidationDeviceProfile?.isDetected(): Boolean =
        this != null && model.isNotBlank()

    private fun ValidationSystemProfile?.isDetected(): Boolean =
        this != null &&
            androidVersion.isNotBlank() &&
            androidApiLevel > 0 &&
            romFamily.isNotBlank() &&
            !romVersion.isNullOrBlank()

    private fun ValidationDeviceProfile?.exactlyMatches(other: ValidationDeviceProfile?): Boolean {
        if (this == null || other == null || model != other.model) return false
        val leftManufacturer = manufacturer?.takeIf { it.isNotBlank() }
        val rightManufacturer = other.manufacturer?.takeIf { it.isNotBlank() }
        return leftManufacturer == null || rightManufacturer == null ||
            leftManufacturer.equals(rightManufacturer, ignoreCase = true)
    }

    private fun ValidationSystemProfile?.isHarmonyOs5Plus(): Boolean {
        if (this == null) return false
        if (romFamily == "HARMONY_OS_5_PLUS") return true
        if (romFamily != "HARMONY_OS") return false
        val version = harmonyOsVersion ?: romVersion
        val major = version?.let { VERSION_NUMBER.find(it)?.value?.toIntOrNull() }
        return major != null && major >= 5
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MISSING_DEVICE_PROFILE = "DEVICE_PROFILE_REQUIRED"
        const val MISSING_SYSTEM_PROFILE = "SYSTEM_PROFILE_REQUIRED"
        const val MISSING_COMPONENT_METADATA = "TRUSTED_COMPONENT_METADATA_REQUIRED"
        const val MISSING_FUNCTIONAL_VALIDATION = "FUNCTIONAL_VALIDATION_REQUIRED"
        const val MISSING_TRUSTED_ARTIFACT_AUDIT = "TRUSTED_HOST_ARTIFACT_AUDIT_REQUIRED"
        const val MISSING_ARTIFACT_PROFILE_BINDING = "ARTIFACT_PROFILE_BINDING_REQUIRED"
        const val MISSING_ARTIFACT_MATCH = "OFFICIAL_ARTIFACT_MATCH_REQUIRED"
        const val BLOCKER_ARTIFACT_MISMATCH = "ARTIFACT_MISMATCH"
        const val BLOCKER_ARTIFACT_PROFILE_MISMATCH = "ARTIFACT_PROFILE_MISMATCH"
        const val BLOCKER_HARMONY_OS_5_PLUS = "HARMONYOS_5_PLUS_LEGACY_PROMOTION_PROHIBITED"

        val REQUIRED_PACKAGES = setOf("com.google.android.gms", "com.android.vending")
        private val VERSION_NUMBER = Regex("\\d+")
    }
}
