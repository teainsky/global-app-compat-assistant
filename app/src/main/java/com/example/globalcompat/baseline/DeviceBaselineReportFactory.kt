package com.example.globalcompat.baseline

import com.example.globalcompat.data.EnvironmentReport
import com.example.globalcompat.data.RomFamily
import com.google.gson.GsonBuilder

class DeviceBaselineReportFactory {
    fun create(
        environment: EnvironmentReport,
        comparisons: List<OfficialComponentComparison>,
        functionalValidation: UserFunctionalValidation,
        capturedAtEpochMillis: Long = System.currentTimeMillis(),
    ): DeviceBaselineReport {
        val device = BaselineDeviceInfo(
            model = environment.device.model,
        )
        val system = BaselineSystemInfo(
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
        val componentReports = comparisons.map { comparison ->
            val fingerprint = comparison.fingerprint
            BaselineComponentReport(
                installed = fingerprint.installed,
                enabled = fingerprint.enabled,
                packageName = fingerprint.packageName,
                versionCode = fingerprint.versionCode,
                versionName = fingerprint.versionName,
                signingCertificateSha256 = fingerprint.signingCertificateSha256,
                installSource = fingerprint.installSource,
                officialMatchStatus = comparison.status,
            )
        }
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
            schemaVersion = 1,
            capturedAtEpochMillis = capturedAtEpochMillis,
            device = device,
            system = system,
            components = componentReports,
            functionalValidation = functionalValidation,
            deviceValidationRecord = validationRecord,
        )
    }

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
            comparisons.all { it.status == OfficialComponentMatchStatus.OFFICIAL_METADATA_MATCH }
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
