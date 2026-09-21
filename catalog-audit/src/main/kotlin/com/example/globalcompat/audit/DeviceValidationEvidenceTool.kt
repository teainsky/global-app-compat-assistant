package com.example.globalcompat.audit

import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.ComponentArtifact
import com.example.globalcompat.validation.DeviceValidationEvidenceInput
import com.example.globalcompat.validation.DeviceValidationEvidenceReport
import com.example.globalcompat.validation.DeviceValidationPromotionPolicy
import com.example.globalcompat.validation.ValidationArtifactComponentEvidence
import com.example.globalcompat.validation.ValidationArtifactEvidence
import com.example.globalcompat.validation.ValidationComponentEvidence
import com.example.globalcompat.validation.ValidationDeviceProfile
import com.example.globalcompat.validation.ValidationEvidenceSource
import com.example.globalcompat.validation.ValidationFunctionalEvidence
import com.example.globalcompat.validation.ValidationSystemProfile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class DeviceValidationEvidenceTool(
    private val promotionPolicy: DeviceValidationPromotionPolicy =
        DeviceValidationPromotionPolicy(),
    private val gson: Gson = Gson(),
) {
    fun evaluate(
        baselinePath: Path,
        artifactAuditPath: Path? = null,
        evaluatedAt: String = Instant.now().toString(),
    ): DeviceValidationEvidenceReport {
        val baseline = Files.newBufferedReader(baselinePath).use { reader ->
            gson.fromJson(reader, ImportedBaseline::class.java)
        }
        require(baseline.schemaVersion == SUPPORTED_BASELINE_SCHEMA) {
            "Unsupported device-baseline schemaVersion: ${baseline.schemaVersion}"
        }
        val deviceProfile = ValidationDeviceProfile(
            manufacturer = null,
            model = baseline.device.model,
        )
        val systemProfile = ValidationSystemProfile(
            harmonyOsVersion = baseline.system.harmonyOsVersion,
            androidVersion = baseline.system.androidVersion,
            androidApiLevel = baseline.system.androidApiLevel,
            romFamily = baseline.system.romFamily,
            romVersion = baseline.system.romVersion,
        )
        val catalogArtifacts = BuiltInComponentCatalog.catalog.releases
            .flatMap { it.artifacts }
            .associateBy { it.packageName }
        val componentEvidence = baseline.components.map { component ->
            val official = catalogArtifacts[component.packageName]
            val matches = official != null && component.matchesMetadata(official)
            ValidationComponentEvidence(
                packageName = component.packageName,
                versionCode = component.versionCode?.toString(),
                versionName = component.versionName,
                metadataMatched = matches,
                source = if (matches) {
                    ValidationEvidenceSource.TRUSTED_CATALOG_COMPARISON
                } else {
                    ValidationEvidenceSource.LOCAL_DEVICE_SCAN
                },
            )
        }
        val functionalEvidence = ValidationFunctionalEvidence(
            googleAccountLogin = baseline.functionalValidation.googleAccountLogin == YES,
            chatGptLoginAndUse = baseline.functionalValidation.chatGptLoginAndUse == YES,
            chromeGoogleLogin = baseline.functionalValidation.chromeGoogleLogin == YES,
            source = ValidationEvidenceSource.USER_CONFIRMATION,
        )
        val artifactEvidence = artifactAuditPath?.let { path ->
            importArtifactEvidence(path, catalogArtifacts)
        }
        val blockedRules = componentEvidence.mapNotNull { component ->
            val official = catalogArtifacts[component.packageName] ?: return@mapNotNull null
            BuiltInComponentCatalog.catalog.blockedVersions.firstOrNull { rule ->
                rule.componentId == official.componentId &&
                    (component.versionCode in rule.versions ||
                        component.versionName in rule.versions ||
                        official.releaseVersion in rule.versions)
            }?.let { rule -> "BLOCKED_VERSION:${rule.componentId}:${rule.reason}" }
        }
        return promotionPolicy.evaluate(
            input = DeviceValidationEvidenceInput(
                deviceProfile = deviceProfile,
                systemProfile = systemProfile,
                componentEvidence = componentEvidence,
                functionalEvidence = functionalEvidence,
                artifactEvidence = artifactEvidence,
                blockedRules = blockedRules,
            ),
            evaluatedAt = evaluatedAt,
        )
    }

    private fun importArtifactEvidence(
        path: Path,
        catalogArtifacts: Map<String, ComponentArtifact>,
    ): ValidationArtifactEvidence {
        val audit = Files.newBufferedReader(path).use { reader ->
            gson.fromJson(reader, ImportedArtifactAudit::class.java)
        }
        require(audit.schemaVersion == SUPPORTED_ARTIFACT_AUDIT_SCHEMA) {
            "Unsupported device-artifact-audit schemaVersion: ${audit.schemaVersion}"
        }
        return ValidationArtifactEvidence(
            deviceProfile = audit.deviceProfile,
            systemProfile = audit.systemProfile,
            components = audit.artifacts.map { artifact ->
                val official = catalogArtifacts[artifact.packageName]
                ValidationArtifactComponentEvidence(
                    packageName = artifact.packageName,
                    sha256 = artifact.locallyCalculatedSha256,
                    signingCertificateSha256 = artifact.signingCertificateSha256,
                    officialArtifactMatched = official != null &&
                        artifact.matchesOfficialArtifact(official),
                )
            },
            // Provenance is assigned by this dedicated import path. Any source field in JSON is ignored.
            source = ValidationEvidenceSource.TRUSTED_HOST_AUDIT,
        )
    }

    private fun ImportedBaselineComponent.matchesMetadata(artifact: ComponentArtifact): Boolean =
        officialMatchStatus == VERSION_MATCH &&
            installed &&
            versionCode?.toString() == artifact.artifactVersionCode &&
            versionName == artifact.artifactVersionName

    private fun ImportedArtifact.matchesOfficialArtifact(artifact: ComponentArtifact): Boolean =
        failure == null &&
            signatureStatus == ACTUAL_ARTIFACT_MATCH &&
            apksignerVerificationResult == PASS &&
            versionCode == artifact.artifactVersionCode &&
            versionName == artifact.artifactVersionName &&
            locallyCalculatedSha256.normalizeDigest() == artifact.sha256.normalizeDigest() &&
            signingCertificateSha256.map { it.normalizeDigest() }.toSet() ==
            setOfNotNull(artifact.signingCertificateDigest?.normalizeDigest())

    private fun String?.normalizeDigest(): String? =
        this?.replace(":", "")?.lowercase()

    private data class ImportedBaseline(
        val schemaVersion: Int,
        val device: ImportedBaselineDevice,
        val system: ImportedBaselineSystem,
        val components: List<ImportedBaselineComponent>,
        val functionalValidation: ImportedFunctionalValidation,
    )

    private data class ImportedBaselineDevice(val model: String)

    private data class ImportedBaselineSystem(
        val harmonyOsVersion: String?,
        val androidVersion: String,
        val androidApiLevel: Int,
        val romFamily: String,
        val romVersion: String?,
    )

    private data class ImportedBaselineComponent(
        val installed: Boolean,
        val packageName: String,
        val versionCode: Long?,
        val versionName: String?,
        val officialMatchStatus: String,
    )

    private data class ImportedFunctionalValidation(
        val googleAccountLogin: String,
        val chatGptLoginAndUse: String,
        val chromeGoogleLogin: String,
    )

    private data class ImportedArtifactAudit(
        val schemaVersion: Int,
        val deviceProfile: ValidationDeviceProfile?,
        val systemProfile: ValidationSystemProfile?,
        val artifacts: List<ImportedArtifact>,
    )

    private data class ImportedArtifact(
        val packageName: String,
        val versionCode: String?,
        val versionName: String?,
        val locallyCalculatedSha256: String?,
        val signingCertificateSha256: List<String>,
        val apksignerVerificationResult: String,
        val signatureStatus: String,
        val failure: String?,
    )

    private companion object {
        const val SUPPORTED_BASELINE_SCHEMA = 2
        const val SUPPORTED_ARTIFACT_AUDIT_SCHEMA = 1
        const val VERSION_MATCH = "VERSION_MATCH"
        const val ACTUAL_ARTIFACT_MATCH = "ACTUAL_ARTIFACT_MATCH"
        const val PASS = "PASS"
        const val YES = "YES"
    }
}

object DeviceValidationEvidenceWriter {
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()

    fun write(report: DeviceValidationEvidenceReport, outputPath: Path) {
        outputPath.parent?.let(Files::createDirectories)
        Files.writeString(outputPath, gson.toJson(report) + "\n")
    }
}
