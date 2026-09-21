package com.example.globalcompat.catalog

import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory

data class CatalogValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
)

class CatalogSchemaValidator(
    private val clientVersion: Long,
) {
    fun validate(catalog: ComponentCatalog): CatalogValidationResult {
        val errors = buildList {
            if (catalog.schemaVersion != CompatibilityCatalogCodec.SUPPORTED_SCHEMA_VERSION) {
                add("unsupported schemaVersion")
            }
            if (catalog.catalogVersion <= 0L) add("catalogVersion must be positive")
            if (catalog.minClientVersion > clientVersion) add("minClientVersion is unsupported")
            if (catalog.publishedAt.isBlank()) add("publishedAt is required")
            validateDeviceRules(catalog.deviceRules, this)
            validateCompatibilityRecords(catalog, this)
            validateArtifacts(catalog, this)
            validateInstallationGate(catalog.installationGatePolicy, this)
        }
        return CatalogValidationResult(errors.isEmpty(), errors)
    }

    private fun validateDeviceRules(
        rules: List<DeviceClassificationRule>,
        errors: MutableList<String>,
    ) {
        if (rules.isEmpty()) errors += "deviceRules must not be empty"
        if (rules.map { it.ruleId }.toSet().size != rules.size) {
            errors += "device rule ids must be unique"
        }
        val harmony5Rules = rules.filter { it.canMatchHarmonyOs5Plus() }
        if (harmony5Rules.none { "HARMONY_OS" in it.romFamilies } ||
            harmony5Rules.none { "HARMONY_OS_5_PLUS" in it.romFamilies }
        ) {
            errors += "HarmonyOS 5+ rules must cover identified and version-derived systems"
        }
        harmony5Rules.forEach { rule ->
            if (rule.deviceCategory != DeviceCategory.HARMONYOS_5_PLUS ||
                rule.planId != CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN ||
                rule.installWorkflowAllowed
            ) {
                errors += "HarmonyOS 5+ rule ${rule.ruleId} weakens the legacy-plan prohibition"
            }
        }
    }

    private fun DeviceClassificationRule.canMatchHarmonyOs5Plus(): Boolean =
        "HARMONY_OS_5_PLUS" in romFamilies ||
            ("HARMONY_OS" in romFamilies &&
                (maxSystemMajor == null || maxSystemMajor >= HARMONY_OS_5_MAJOR) &&
                (minSystemMajor == null || minSystemMajor <= HARMONY_OS_5_MAJOR))

    private fun validateCompatibilityRecords(
        catalog: ComponentCatalog,
        errors: MutableList<String>,
    ) {
        val releaseIds = catalog.releases.map { it.releaseId }.toSet()
        val recordKeys = catalog.compatibilityRecords.map { it.releaseId to it.componentId }
        if (recordKeys.toSet().size != recordKeys.size) {
            errors += "compatibility record keys must be unique"
        }
        catalog.compatibilityRecords.forEach { record ->
            val release = catalog.releases.firstOrNull { it.releaseId == record.releaseId }
            if (record.releaseId !in releaseIds || release == null) {
                errors += "compatibility record references an unknown release"
            }
            if (record.componentId != null &&
                release?.artifacts?.none { it.componentId == record.componentId } != false
            ) {
                errors += "compatibility record references an unknown component"
            }
            if (record.status == CompatibilityValidationStatus.DEVICE_VERIFIED &&
                record.authority != CompatibilityEvidenceAuthority.TRUSTED_DEVICE_LAB
            ) {
                errors += "DEVICE_VERIFIED requires trusted device-lab evidence"
            }
        }
        catalog.releases.forEach { release ->
            if ((release.releaseId to null) !in recordKeys) {
                errors += "release compatibility record is required"
            }
            release.artifacts.forEach { artifact ->
                if ((release.releaseId to artifact.componentId) !in recordKeys) {
                    errors += "artifact compatibility record is required"
                }
            }
        }
    }

    private fun validateArtifacts(
        catalog: ComponentCatalog,
        errors: MutableList<String>,
    ) {
        val allowedSources = catalog.verificationPolicy.allowedSourceTypes
        catalog.releases.forEach { release ->
            if (release.releaseId.isBlank() || release.releaseTag.isBlank() ||
                release.releaseVersion.isBlank()
            ) {
                errors += "release metadata must be explicit"
            }
            release.artifacts.forEach { artifact ->
                if (artifact.componentId.isBlank() || artifact.packageName.isBlank() ||
                    artifact.artifactFilename.isNullOrBlank() ||
                    artifact.artifactVersionCode.isNullOrBlank() ||
                    artifact.artifactVersionName.isNullOrBlank()
                ) {
                    errors += "artifact metadata must be explicit"
                }
                if (artifact.sourceType !in allowedSources) {
                    errors += "artifact source is not allowed"
                }
                if (artifact.integrityStatus == ArtifactIntegrityStatus.SIGNATURE_VERIFIED &&
                    (!artifact.sha256.isSha256() ||
                        !artifact.signingCertificateDigest.isSha256())
                ) {
                    errors += "signature-verified artifact lacks audited digests"
                }
            }
        }
    }

    private fun validateInstallationGate(
        gate: InstallationGatePolicy,
        errors: MutableList<String>,
    ) {
        if (gate.allowedDeviceCategories !=
            setOf(DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT)
        ) {
            errors += "installation gate device branch is not fail-closed"
        }
        if (gate.requiredSourceAvailability != SourceAvailabilityStatus.AVAILABLE ||
            gate.requiredIntegrityStatus != ArtifactIntegrityStatus.SIGNATURE_VERIFIED ||
            gate.requiredCompatibilityStatus != CompatibilityValidationStatus.DEVICE_VERIFIED ||
            !gate.requireSha256 ||
            !gate.requireSigningCertificate ||
            !gate.requirePackageNameMatch ||
            !gate.requireVersionMatch ||
            !gate.blockHarmonyOs5PlusLegacyPlan
        ) {
            errors += "installation gate weakens mandatory evidence requirements"
        }
    }

    private fun String?.isSha256(): Boolean =
        this != null && SHA256.matches(replace(":", "").lowercase())

    private companion object {
        const val HARMONY_OS_5_MAJOR = 5
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
