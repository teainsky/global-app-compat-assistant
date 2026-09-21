package com.example.globalcompat.audit

import com.example.globalcompat.catalog.ArtifactIntegrityStatus
import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.CompatibilityValidationStatus
import com.example.globalcompat.catalog.ComponentArtifact
import com.example.globalcompat.catalog.ComponentCatalog
import com.example.globalcompat.catalog.ComponentVariant
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.validation.DeviceRecordPublicationRejection
import com.example.globalcompat.validation.DeviceRecordPublicationResult
import com.example.globalcompat.validation.DeviceValidationEvidenceInput
import com.example.globalcompat.validation.DeviceValidationEvidenceLevel
import com.example.globalcompat.validation.DeviceValidationEvidenceReport
import com.example.globalcompat.validation.DeviceValidationPromotionPolicy
import com.example.globalcompat.validation.PublishedCompatibilityStatus
import com.example.globalcompat.validation.ValidationEvidenceSource
import com.example.globalcompat.validation.VerifiedDeviceCompatibilityRecord
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class DeviceVerificationPublishingPipeline(
    private val catalog: ComponentCatalog = BuiltInComponentCatalog.catalog,
    private val promotionPolicy: DeviceValidationPromotionPolicy =
        DeviceValidationPromotionPolicy(),
    private val gson: Gson = Gson(),
) {
    fun publish(
        evidenceBytes: ByteArray,
        expectedEvidenceSha256: String,
    ): DeviceRecordPublicationResult = runCatching {
        publishChecked(evidenceBytes, expectedEvidenceSha256)
    }.getOrElse {
        rejected(DeviceRecordPublicationRejection.EVIDENCE_SCHEMA_UNSUPPORTED)
    }

    private fun publishChecked(
        evidenceBytes: ByteArray,
        expectedEvidenceSha256: String,
    ): DeviceRecordPublicationResult {
        val expectedDigest = expectedEvidenceSha256.normalizeDigest()
        if (!SHA256.matches(expectedDigest)) {
            return rejected(DeviceRecordPublicationRejection.EVIDENCE_DIGEST_INVALID)
        }
        val actualDigest = sha256(evidenceBytes)
        if (actualDigest != expectedDigest) {
            return rejected(DeviceRecordPublicationRejection.EVIDENCE_DIGEST_MISMATCH)
        }
        val report = runCatching {
            gson.fromJson(evidenceBytes.decodeToString(), DeviceValidationEvidenceReport::class.java)
        }.getOrNull() ?: return rejected(
            DeviceRecordPublicationRejection.EVIDENCE_SCHEMA_UNSUPPORTED,
        )
        if (report.schemaVersion != DeviceValidationPromotionPolicy.SCHEMA_VERSION) {
            return rejected(DeviceRecordPublicationRejection.EVIDENCE_SCHEMA_UNSUPPORTED)
        }
        if (report.attainedLevel != DeviceValidationEvidenceLevel.DEVICE_VERIFIED) {
            return rejected(DeviceRecordPublicationRejection.EVIDENCE_LEVEL_NOT_DEVICE_VERIFIED)
        }
        if (report.missingEvidence.isNotEmpty() || report.blockers.isNotEmpty()) {
            return rejected(DeviceRecordPublicationRejection.EVIDENCE_INCOMPLETE)
        }

        val system = report.systemProfile
            ?: return rejected(DeviceRecordPublicationRejection.EXACT_PROFILE_MISMATCH)
        val device = report.deviceProfile
            ?: return rejected(DeviceRecordPublicationRejection.EXACT_PROFILE_MISMATCH)
        val artifactEvidence = report.artifactEvidence
            ?: return rejected(DeviceRecordPublicationRejection.ARTIFACT_EVIDENCE_REQUIRED)
        val reevaluated = promotionPolicy.evaluate(
            input = DeviceValidationEvidenceInput(
                deviceProfile = device,
                systemProfile = system,
                componentEvidence = report.componentEvidence,
                functionalEvidence = report.functionalEvidence,
                artifactEvidence = artifactEvidence,
                blockedRules = report.blockers,
            ),
            evaluatedAt = report.evaluatedAt,
        )
        if (reevaluated.attainedLevel != DeviceValidationEvidenceLevel.DEVICE_VERIFIED) {
            val reason = when {
                DeviceValidationPromotionPolicy.BLOCKER_HARMONY_OS_5_PLUS in
                    reevaluated.blockers ->
                    DeviceRecordPublicationRejection.HARMONYOS_5_PLUS_NOT_PUBLISHABLE
                DeviceValidationPromotionPolicy.BLOCKER_ARTIFACT_MISMATCH in
                    reevaluated.blockers -> DeviceRecordPublicationRejection.ARTIFACT_MISMATCH
                else -> DeviceRecordPublicationRejection.EXACT_PROFILE_MISMATCH
            }
            return rejected(reason)
        }

        val harmonyMajor = system.harmonyOsVersion.majorVersion()
        if (system.romFamily != HARMONY_OS || harmonyMajor == null || harmonyMajor >= 5) {
            return rejected(DeviceRecordPublicationRejection.HARMONYOS_5_PLUS_NOT_PUBLISHABLE)
        }
        val matchingReleases = catalog.releases.filter { release ->
            release.compatibility.requiredPlanId == CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN &&
                release.compatibility.requiredDeviceCategory ==
                DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT &&
                harmonyMajor in release.compatibility.minHarmonyOsMajor..
                release.compatibility.maxHarmonyOsMajor &&
                release.compatibilityStatus != CompatibilityValidationStatus.BLOCKED &&
                release.compatibilityStatus != CompatibilityValidationStatus.DEPRECATED &&
                release.artifacts.matchesEvidence(report)
        }
        val release = matchingReleases.singleOrNull()
            ?: return rejected(DeviceRecordPublicationRejection.CATALOG_RELEASE_NOT_FOUND)
        if (release.artifacts.any { artifact ->
                catalog.isBlocked(artifact) ||
                    artifact.compatibilityStatus == CompatibilityValidationStatus.BLOCKED ||
                    artifact.compatibilityStatus == CompatibilityValidationStatus.DEPRECATED ||
                    device.model in artifact.blockedDeviceFamilies ||
                    system.romVersion?.let { it in artifact.blockedSystemVersions } == true
            }
        ) {
            return rejected(DeviceRecordPublicationRejection.BLOCKED_VERSION)
        }

        val artifactsByPackage = artifactEvidence.components.associateBy { it.packageName }
        return DeviceRecordPublicationResult.Approved(
            VerifiedDeviceCompatibilityRecord(
                schemaVersion = RECORD_SCHEMA_VERSION,
                deviceModel = device.model,
                // V1 deliberately uses the exact model as the family key; no sibling-model inference.
                deviceFamily = device.model,
                romFamily = system.romFamily,
                harmonyOsVersion = system.harmonyOsVersion.orEmpty(),
                androidApiLevel = system.androidApiLevel,
                componentRelease = release.releaseTag,
                componentVersionCodes = release.artifacts.associate { artifact ->
                    artifact.packageName to artifact.artifactVersionCode.orEmpty()
                }.toSortedMap(),
                componentSignerDigests = release.artifacts.associate { artifact ->
                    artifact.packageName to artifactsByPackage
                        .getValue(artifact.packageName)
                        .signingCertificateSha256
                        .map { it.normalizeDigest() }
                        .sorted()
                }.toSortedMap(),
                validationDate = report.evaluatedAt,
                evidenceDigest = actualDigest,
                compatibilityStatus = PublishedCompatibilityStatus.DEVICE_VERIFIED,
            ),
        )
    }

    private fun List<ComponentArtifact>.matchesEvidence(
        report: DeviceValidationEvidenceReport,
    ): Boolean {
        val requiredPackages = DeviceValidationPromotionPolicy.REQUIRED_PACKAGES
        val relevant = filter { it.packageName in requiredPackages }
        if (relevant.size != requiredPackages.size ||
            relevant.mapTo(mutableSetOf()) { it.packageName } != requiredPackages
        ) {
            return false
        }
        val metadata = report.componentEvidence.associateBy { it.packageName }
        val artifactEvidence = report.artifactEvidence ?: return false
        val artifacts = artifactEvidence.components.associateBy { it.packageName }
        return relevant.all { expected ->
            val component = metadata[expected.packageName]
            val artifact = artifacts[expected.packageName]
            expected.integrityStatus == ArtifactIntegrityStatus.SIGNATURE_VERIFIED &&
                expected.variant == ComponentVariant.HUAWEI_HW &&
                component?.source == ValidationEvidenceSource.TRUSTED_CATALOG_COMPARISON &&
                component.metadataMatched &&
                component.versionCode == expected.artifactVersionCode &&
                component.versionName == expected.artifactVersionName &&
                artifactEvidence.source in setOf(
                    ValidationEvidenceSource.TRUSTED_HOST_AUDIT,
                    ValidationEvidenceSource.DEVELOPER_REVIEWED_ON_DEVICE_AUDIT,
                ) &&
                artifact?.officialArtifactMatched == true &&
                artifact.sha256.normalizeDigest() == expected.sha256.normalizeDigest() &&
                artifact.signingCertificateSha256.map { it.normalizeDigest() }.toSet() ==
                setOfNotNull(expected.signingCertificateDigest?.normalizeDigest())
        }
    }

    private fun ComponentCatalog.isBlocked(artifact: ComponentArtifact): Boolean =
        blockedVersions.any { rule ->
            rule.componentId == artifact.componentId &&
                (artifact.releaseVersion in rule.versions ||
                    artifact.artifactVersionCode in rule.versions ||
                    artifact.artifactVersionName in rule.versions)
        }

    private fun String?.majorVersion(): Int? =
        this?.let { VERSION_NUMBER.find(it)?.value?.toIntOrNull() }

    private fun String?.normalizeDigest(): String =
        this?.replace(":", "")?.lowercase().orEmpty()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun rejected(vararg reasons: DeviceRecordPublicationRejection) =
        DeviceRecordPublicationResult.Rejected(reasons.distinct())

    private companion object {
        const val RECORD_SCHEMA_VERSION = 1
        const val HARMONY_OS = "HARMONY_OS"
        val SHA256 = Regex("[0-9a-f]{64}")
        val VERSION_NUMBER = Regex("\\d+")
    }
}

object VerifiedDeviceRecordWriter {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun write(record: VerifiedDeviceCompatibilityRecord, outputPath: Path) {
        outputPath.parent?.let(Files::createDirectories)
        Files.writeString(outputPath, gson.toJson(record) + "\n")
    }
}
