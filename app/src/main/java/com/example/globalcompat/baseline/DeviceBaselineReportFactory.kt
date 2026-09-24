package com.example.globalcompat.baseline

import com.example.globalcompat.artifact.OnDeviceArtifactAuditReport
import com.example.globalcompat.artifact.OnDeviceArtifactComponentResult
import com.example.globalcompat.catalog.InstalledArtifactSignatureStatus
import com.example.globalcompat.data.EnvironmentReport
import com.example.globalcompat.data.RomFamily
import com.example.globalcompat.validation.DeviceValidationEvidenceInput
import com.example.globalcompat.validation.DeviceValidationPromotionPolicy
import com.example.globalcompat.validation.ValidationComponentEvidence
import com.example.globalcompat.validation.ValidationDeviceProfile
import com.example.globalcompat.validation.ValidationEvidenceSource
import com.example.globalcompat.validation.ValidationFunctionalEvidence
import com.example.globalcompat.validation.ValidationSystemProfile
import com.google.gson.GsonBuilder

class DeviceBaselineReportFactory {
    fun create(
        environment: EnvironmentReport,
        comparisons: List<OfficialComponentComparison>,
        functionalValidation: UserFunctionalValidation,
        capturedAtEpochMillis: Long = System.currentTimeMillis(),
        artifactAuditReport: OnDeviceArtifactAuditReport? = null,
    ): DeviceBaselineReport {
        val device = BaselineDeviceInfo(
            model = environment.device.model,
        )
        val system = BaselineSystemInfo(
            runtimeEnvironment = environment.deviceProfile.runtimeEnvironment.name,
            harmonyOsVersion = environment.rom.version.takeIf {
                environment.rom.family == RomFamily.HARMONY_OS ||
                    environment.rom.family == RomFamily.HARMONY_OS_5_PLUS
            },
            androidVersion = environment.android.release,
            androidApiLevel = environment.android.apiLevel,
            romFamily = environment.rom.family.name,
            romDisplayName = environment.rom.displayName,
            romVersion = environment.rom.version,
        )
        val artifactAuditsByPackage = artifactAuditReport?.components
            ?.associateBy { it.packageName }
            .orEmpty()
        val componentReports = comparisons.map { comparison ->
            val fingerprint = comparison.fingerprint
            BaselineComponentReport(
                installed = fingerprint.installed,
                enabled = fingerprint.enabled,
                packageName = fingerprint.packageName,
                versionCode = fingerprint.versionCode,
                versionName = fingerprint.versionName,
                reportedSigningCertificateSha256 =
                    fingerprint.reportedSigningCertificateSha256,
                installSource = fingerprint.installSource,
                officialMatchStatus = comparison.status,
                signatureStatus = comparison.signatureStatus,
                artifactAudit = artifactAuditsByPackage[fingerprint.packageName]
                    .toBaselineArtifactAudit(),
            )
        }
        val attainedEvidenceLevel = DeviceValidationPromotionPolicy().evaluate(
            input = DeviceValidationEvidenceInput(
                deviceProfile = ValidationDeviceProfile(
                    manufacturer = environment.device.manufacturer,
                    model = environment.device.model,
                ),
                systemProfile = ValidationSystemProfile(
                    harmonyOsVersion = system.harmonyOsVersion,
                    androidVersion = system.androidVersion,
                    androidApiLevel = system.androidApiLevel,
                    romFamily = system.romFamily,
                    romVersion = system.romVersion,
                ),
                componentEvidence = comparisons.map { comparison ->
                    ValidationComponentEvidence(
                        packageName = comparison.fingerprint.packageName,
                        versionCode = comparison.fingerprint.versionCode?.toString(),
                        versionName = comparison.fingerprint.versionName,
                        metadataMatched = comparison.status ==
                            OfficialComponentMatchStatus.VERSION_MATCH,
                        source = ValidationEvidenceSource.TRUSTED_CATALOG_COMPARISON,
                    )
                },
                functionalEvidence = ValidationFunctionalEvidence(
                    googleAccountLogin =
                        functionalValidation.googleAccountLogin == UserValidationAnswer.YES,
                    chatGptLoginAndUse =
                        functionalValidation.chatGptLoginAndUse == UserValidationAnswer.YES,
                    chromeGoogleLogin =
                        functionalValidation.chromeGoogleLogin == UserValidationAnswer.YES,
                    source = ValidationEvidenceSource.USER_CONFIRMATION,
                ),
                artifactEvidence = artifactAuditReport?.artifactEvidence,
            ),
            evaluatedAt = capturedAtEpochMillis.toString(),
        ).attainedLevel
        val validationRecord = createValidationRecord(
            capturedAtEpochMillis,
            device,
            system,
            comparisons,
            functionalValidation,
            hasRealDeviceInfo = environment.device.manufacturer.isNotBlank() &&
                environment.device.model.isNotBlank() &&
                environment.android.apiLevel > 0 &&
                environment.android.release.isNotBlank() &&
                environment.rom.displayName.isNotBlank(),
        )
        return DeviceBaselineReport(
            schemaVersion = 3,
            capturedAtEpochMillis = capturedAtEpochMillis,
            device = device,
            system = system,
            components = componentReports,
            functionalValidation = functionalValidation,
            attainedEvidenceLevel = attainedEvidenceLevel,
            deviceValidationRecord = validationRecord,
        )
    }

    private fun OnDeviceArtifactComponentResult?.toBaselineArtifactAudit():
        BaselineArtifactAudit = BaselineArtifactAudit(
        readStatus = this?.readStatus?.name,
        installedApkSha256 = this?.installedApkSha256,
        officialApkSha256 = this?.officialApkSha256,
        artifactMatchStatus = when {
            this == null -> BaselineArtifactMatchStatus.NOT_AUDITED
            officialArtifactMatched -> BaselineArtifactMatchStatus.ACTUAL_ARTIFACT_MATCH
            installedApkSha256 != null -> BaselineArtifactMatchStatus.NOT_MATCHED
            else -> BaselineArtifactMatchStatus.UNAVAILABLE
        },
    )

    private fun createValidationRecord(
        capturedAtEpochMillis: Long,
        device: BaselineDeviceInfo,
        system: BaselineSystemInfo,
        comparisons: List<OfficialComponentComparison>,
        functionalValidation: UserFunctionalValidation,
        hasRealDeviceInfo: Boolean,
    ): DeviceValidationRecord? {
        val requiredPackages = InstalledComponentFingerprintScanner.TARGET_PACKAGES.toSet()
        val matchesOfficialPair = comparisons.size == requiredPackages.size &&
            comparisons.mapTo(mutableSetOf()) { it.fingerprint.packageName } == requiredPackages &&
            comparisons.all {
                it.status == OfficialComponentMatchStatus.VERSION_MATCH &&
                    it.signatureStatus == InstalledArtifactSignatureStatus.ACTUAL_ARTIFACT_MATCH
            }
        if (!matchesOfficialPair || !hasRealDeviceInfo || !functionalValidation.allSuccessful()) {
            return null
        }
        return DeviceValidationRecord(
            schemaVersion = 1,
            validatedAtEpochMillis = capturedAtEpochMillis,
            deviceModel = device.model,
            harmonyOsVersion = system.harmonyOsVersion,
            androidVersion = system.androidVersion,
            androidApiLevel = system.androidApiLevel,
            romFamily = system.romFamily,
            matchedPackages = comparisons.map { it.fingerprint.packageName }.sorted(),
            functionalValidation = functionalValidation,
        )
    }
}

object DeviceBaselineJsonExporter {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun toJson(report: DeviceBaselineReport): String = gson.toJson(report) + "\n"
}
