package com.example.globalcompat.baseline

import com.example.globalcompat.artifact.OnDeviceArtifactAuditAvailability
import com.example.globalcompat.artifact.OnDeviceArtifactAuditReport
import com.example.globalcompat.artifact.OnDeviceArtifactComponentResult
import com.example.globalcompat.artifact.OnDeviceArtifactReadStatus
import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.InstalledArtifactSignatureStatus
import com.example.globalcompat.data.AndroidPlatform
import com.example.globalcompat.data.CompatibilityPlan
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.CompatibilityPlanStatus
import com.example.globalcompat.data.CompatibilityLayerAssessment
import com.example.globalcompat.data.DetectionConfidence
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.data.DeviceIdentity
import com.example.globalcompat.data.DeviceProfile
import com.example.globalcompat.data.EnvironmentReport
import com.example.globalcompat.data.GlobalValidationLevel
import com.example.globalcompat.data.GoogleEnvironment
import com.example.globalcompat.data.GoogleCompatibilityLayerStatus
import com.example.globalcompat.data.InstallationCapability
import com.example.globalcompat.data.MarketVariant
import com.example.globalcompat.data.OsFamily
import com.example.globalcompat.data.PlatformFamily
import com.example.globalcompat.data.RomFamily
import com.example.globalcompat.data.RomIdentification
import com.example.globalcompat.validation.DeviceValidationEvidenceLevel
import com.example.globalcompat.validation.ValidationArtifactComponentEvidence
import com.example.globalcompat.validation.ValidationArtifactEvidence
import com.example.globalcompat.validation.ValidationDeviceProfile
import com.example.globalcompat.validation.ValidationEvidenceSource
import com.example.globalcompat.validation.ValidationSystemProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBaselineTest {
    private val matcher = OfficialComponentMatcher(BuiltInComponentCatalog.catalog)
    private val factory = DeviceBaselineReportFactory()

    @Test
    fun `reported artifact signer does not prove original APK bytes`() {
        val comparisons = matcher.compare(officialPair())

        assertEquals(2, comparisons.size)
        assertTrue(comparisons.all {
            it.status == OfficialComponentMatchStatus.VERSION_MATCH &&
                it.signatureStatus == InstalledArtifactSignatureStatus.UNKNOWN
        })
    }

    @Test
    fun `Huawei hw build with Google reported signer is compatibility signature`() {
        val comparisons = matcher.compare(
            fingerprints = googleReportedPair(),
            context = harmonyContext(),
        )

        assertTrue(comparisons.all {
            it.status == OfficialComponentMatchStatus.VERSION_MATCH &&
                it.signatureStatus ==
                InstalledArtifactSignatureStatus.COMPATIBILITY_SIGNATURE_REPORTED
        })
    }

    @Test
    fun `different version reports version mismatch`() {
        val fingerprints = officialPair().map { fingerprint ->
            if (fingerprint.packageName == GMS_PACKAGE) {
                fingerprint.copy(versionCode = fingerprint.versionCode?.minus(1))
            } else {
                fingerprint
            }
        }

        val comparisons = matcher.compare(fingerprints)

        assertEquals(
            OfficialComponentMatchStatus.VERSION_MISMATCH,
            comparisons.single { it.fingerprint.packageName == GMS_PACKAGE }.status,
        )
    }

    @Test
    fun `random reported signer remains signer mismatch`() {
        val fingerprints = officialPair().map { fingerprint ->
            if (fingerprint.packageName == GMS_PACKAGE) {
                fingerprint.copy(
                    reportedSigningCertificateSha256 = listOf(RANDOM_SIGNER),
                )
            } else {
                fingerprint
            }
        }

        val comparisons = matcher.compare(fingerprints, harmonyContext())

        assertEquals(
            InstalledArtifactSignatureStatus.SIGNER_MISMATCH,
            comparisons.single { it.fingerprint.packageName == GMS_PACKAGE }.signatureStatus,
        )
    }

    @Test
    fun `one missing component is not installed while the other can match`() {
        val fingerprints = listOf(
            officialFingerprint(GMS_PACKAGE),
            missingFingerprint(VENDING_PACKAGE),
        )

        val comparisons = matcher.compare(fingerprints)

        assertEquals(
            OfficialComponentMatchStatus.VERSION_MATCH,
            comparisons.single { it.fingerprint.packageName == GMS_PACKAGE }.status,
        )
        assertEquals(
            OfficialComponentMatchStatus.NOT_INSTALLED,
            comparisons.single { it.fingerprint.packageName == VENDING_PACKAGE }.status,
        )
    }

    @Test
    fun `both missing components report not installed`() {
        val comparisons = matcher.compare(
            listOf(missingFingerprint(GMS_PACKAGE), missingFingerprint(VENDING_PACKAGE)),
        )

        assertTrue(comparisons.all { it.status == OfficialComponentMatchStatus.NOT_INSTALLED })
    }

    @Test
    fun `package lookup failure reports unreadable`() {
        val scanner = InstalledComponentFingerprintScanner {
            throw SecurityException("PackageManager unavailable")
        }

        val fingerprints = scanner.scan()
        val comparisons = matcher.compare(fingerprints)

        assertTrue(fingerprints.all {
            it.readStatus == ComponentFingerprintReadStatus.UNREADABLE
        })
        assertTrue(comparisons.all { it.status == OfficialComponentMatchStatus.UNREADABLE })
    }

    @Test
    fun `successful user confirmations cannot bypass signer mismatch`() {
        val mismatched = officialPair().map { fingerprint ->
            if (fingerprint.packageName == GMS_PACKAGE) {
                fingerprint.copy(
                    reportedSigningCertificateSha256 = listOf(RANDOM_SIGNER),
                )
            } else {
                fingerprint
            }
        }

        val report = factory.create(environment(), matcher.compare(mismatched), allYes(), NOW)

        assertNull(report.deviceValidationRecord)
    }

    @Test
    fun `failed user confirmation cannot create successful baseline`() {
        val userValidation = allYes().copy(chatGptLoginAndUse = UserValidationAnswer.NO)

        val report = factory.create(
            environment(),
            matcher.compare(
                officialPair(),
                harmonyContext(),
                OFFICIAL_HASHES,
            ),
            userValidation,
            NOW,
        )

        assertNull(report.deviceValidationRecord)
    }

    @Test
    fun `official pair and successful confirmations create local validation record`() {
        val report = factory.create(
            environment(),
            matcher.compare(
                officialPair(),
                harmonyContext(),
                OFFICIAL_HASHES,
            ),
            allYes(),
            NOW,
        )

        val record = requireNotNull(report.deviceValidationRecord)
        assertEquals(NOW, record.validatedAtEpochMillis)
        assertEquals(listOf(VENDING_PACKAGE, GMS_PACKAGE).sorted(), record.matchedPackages)
    }

    @Test
    fun `baseline export includes artifact audit and caps evidence at artifact verified`() {
        val report = factory.create(
            environment = environment(),
            comparisons = matcher.compare(officialPair(), harmonyContext()),
            functionalValidation = allYes(),
            capturedAtEpochMillis = NOW,
            artifactAuditReport = successfulArtifactAudit(),
        )

        assertEquals(3, report.schemaVersion)
        assertEquals(
            DeviceValidationEvidenceLevel.ARTIFACT_VERIFIED,
            report.attainedEvidenceLevel,
        )
        assertNull(report.deviceValidationRecord)
        assertTrue(report.components.all {
            it.artifactAudit.artifactMatchStatus ==
                BaselineArtifactMatchStatus.ACTUAL_ARTIFACT_MATCH &&
                it.artifactAudit.installedApkSha256 == OFFICIAL_HASHES[it.packageName] &&
                it.artifactAudit.officialApkSha256 == OFFICIAL_HASHES[it.packageName]
        })

        val json = DeviceBaselineJsonExporter.toJson(report)
        assertTrue(json.contains("\"artifactAudit\""))
        assertTrue(json.contains("\"installedApkSha256\""))
        assertTrue(json.contains("\"officialApkSha256\""))
        assertTrue(json.contains("\"artifactMatchStatus\": \"ACTUAL_ARTIFACT_MATCH\""))
        assertTrue(json.contains("\"attainedEvidenceLevel\": \"ARTIFACT_VERIFIED\""))
        assertFalse(json.contains("\"attainedEvidenceLevel\": \"DEVICE_VERIFIED\""))
    }

    @Test
    fun `baseline without artifact audit records not audited and stays functional`() {
        val report = factory.create(
            environment = environment(),
            comparisons = matcher.compare(officialPair(), harmonyContext()),
            functionalValidation = allYes(),
            capturedAtEpochMillis = NOW,
        )

        assertEquals(
            DeviceValidationEvidenceLevel.FUNCTIONALLY_VALIDATED,
            report.attainedEvidenceLevel,
        )
        assertTrue(report.components.all {
            it.artifactAudit.artifactMatchStatus == BaselineArtifactMatchStatus.NOT_AUDITED &&
                it.artifactAudit.installedApkSha256 == null &&
                it.artifactAudit.officialApkSha256 == null
        })
    }

    @Test
    fun `baseline JSON excludes device unique identifiers`() {
        val report = factory.create(
            environment(),
            matcher.compare(
                officialPair(),
                harmonyContext(),
                OFFICIAL_HASHES,
            ),
            allYes(),
            NOW,
        )

        val json = DeviceBaselineJsonExporter.toJson(report)
        val lowercase = json.lowercase()
        listOf(
            "androidid",
            "android_id",
            "imei",
            "serialnumber",
            "serial_number",
            "macaddress",
            "mac_address",
            "phonenumber",
            "phone_number",
            "simidentifier",
            "sim_identifier",
            "contacts",
        ).forEach { forbiddenKey ->
            assertFalse("forbidden key: $forbiddenKey", lowercase.contains(forbiddenKey))
        }
        assertFalse(json.contains("UNIQUE_PRODUCT_CODE"))
        assertFalse(json.contains("UNIQUE_DEVICE_CODE"))
        assertFalse(json.contains("UNIQUE_HARDWARE_CODE"))
        assertFalse(json.contains("UNIQUE_BUILD_FINGERPRINT"))
        assertTrue(json.contains("Huawei Pura 70 Pro+"))
    }

    private fun officialPair() = listOf(
        officialFingerprint(GMS_PACKAGE),
        officialFingerprint(VENDING_PACKAGE),
    )

    private fun googleReportedPair() = officialPair().map {
        it.copy(reportedSigningCertificateSha256 = listOf(GOOGLE_SIGNER))
    }

    private fun harmonyContext() = ComponentMatchContext(
        manufacturer = "HUAWEI",
        romFamily = RomFamily.HARMONY_OS,
        romVersion = "4.2",
    )

    private fun officialFingerprint(packageName: String): InstalledComponentFingerprint =
        when (packageName) {
            GMS_PACKAGE -> fingerprint(
                packageName = packageName,
                versionCode = 252432032L,
                versionName = "0.3.16.252432-hw",
            )
            else -> fingerprint(
                packageName = packageName,
                versionCode = 84022632L,
                versionName = "0.3.16.40226-hw",
            )
        }

    private fun fingerprint(
        packageName: String,
        versionCode: Long,
        versionName: String,
    ) = InstalledComponentFingerprint(
        installed = true,
        enabled = true,
        packageName = packageName,
        versionCode = versionCode,
        versionName = versionName,
        reportedSigningCertificateSha256 = listOf(OFFICIAL_SIGNER),
        installSource = "com.huawei.appmarket",
        readStatus = ComponentFingerprintReadStatus.READABLE,
    )

    private fun missingFingerprint(packageName: String) = InstalledComponentFingerprint(
        installed = false,
        enabled = null,
        packageName = packageName,
        versionCode = null,
        versionName = null,
        reportedSigningCertificateSha256 = emptyList(),
        installSource = null,
        readStatus = ComponentFingerprintReadStatus.NOT_INSTALLED,
    )

    private fun allYes() = UserFunctionalValidation(
        googleAccountLogin = UserValidationAnswer.YES,
        chatGptLoginAndUse = UserValidationAnswer.YES,
        chromeGoogleLogin = UserValidationAnswer.YES,
    )

    private fun successfulArtifactAudit(): OnDeviceArtifactAuditReport {
        val deviceProfile = ValidationDeviceProfile(
            manufacturer = "HUAWEI",
            model = "Huawei Pura 70 Pro+",
        )
        val systemProfile = ValidationSystemProfile(
            harmonyOsVersion = "4.2",
            androidVersion = "12",
            androidApiLevel = 31,
            romFamily = RomFamily.HARMONY_OS.name,
            romVersion = "4.2",
        )
        val components = officialPair().map { fingerprint ->
            val sha256 = checkNotNull(OFFICIAL_HASHES[fingerprint.packageName])
            OnDeviceArtifactComponentResult(
                packageName = fingerprint.packageName,
                readStatus = OnDeviceArtifactReadStatus.AUDITED,
                versionCode = fingerprint.versionCode.toString(),
                versionName = fingerprint.versionName,
                reportedSigningCertificateSha256 = listOf(OFFICIAL_SIGNER),
                splitApkCount = 0,
                installedApkSha256 = sha256,
                officialApkSha256 = sha256,
                packageMatched = true,
                versionMatched = true,
                signerAccepted = true,
                officialArtifactMatched = true,
                failureType = null,
            )
        }
        return OnDeviceArtifactAuditReport(
            schemaVersion = 1,
            availability =
                OnDeviceArtifactAuditAvailability.ON_DEVICE_ARTIFACT_AUDIT_SUPPORTED,
            components = components,
            artifactEvidence = ValidationArtifactEvidence(
                deviceProfile = deviceProfile,
                systemProfile = systemProfile,
                components = components.map { component ->
                    ValidationArtifactComponentEvidence(
                        packageName = component.packageName,
                        sha256 = component.installedApkSha256,
                        signingCertificateSha256 =
                            component.reportedSigningCertificateSha256,
                        officialArtifactMatched = component.officialArtifactMatched,
                    )
                },
                source = ValidationEvidenceSource.ON_DEVICE_READ_ONLY_AUDIT,
            ),
            attainedEvidenceLevel = DeviceValidationEvidenceLevel.ARTIFACT_VERIFIED,
            auditedAtEpochMillis = NOW,
        )
    }

    private fun environment() = EnvironmentReport(
        schemaVersion = 3,
        scannedAtEpochMillis = NOW,
        device = DeviceIdentity(
            brand = "HUAWEI",
            manufacturer = "HUAWEI",
            model = "Huawei Pura 70 Pro+",
            product = "UNIQUE_PRODUCT_CODE",
            device = "UNIQUE_DEVICE_CODE",
            hardware = "UNIQUE_HARDWARE_CODE",
            board = "UNIQUE_BOARD_CODE",
            supportedAbis = listOf("arm64-v8a"),
        ),
        android = AndroidPlatform(
            apiLevel = 31,
            release = "12",
            securityPatch = "2026-01-01",
            buildDisplay = "HarmonyOS 4.2",
            buildIncremental = "UNIQUE_INCREMENTAL",
            fingerprint = "UNIQUE_BUILD_FINGERPRINT",
        ),
        rom = RomIdentification(
            family = RomFamily.HARMONY_OS,
            displayName = "HarmonyOS",
            version = "4.2",
            confidence = DetectionConfidence.HIGH,
            evidence = emptyList(),
        ),
        components = emptyList(),
        googleCompatibilityLayer = GoogleCompatibilityLayerStatus(
            assessment = CompatibilityLayerAssessment.NOT_ASSESSED,
            note = "Not assessed",
        ),
        deviceProfile = DeviceProfile(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            model = "Huawei Pura 70 Pro+",
            deviceFamily = "Huawei Pura 70 Pro+",
            marketVariant = MarketVariant.UNKNOWN,
            platformFamily = PlatformFamily.HARMONY_ANDROID_COMPAT,
            osFamily = OsFamily.HARMONY_OS,
            osVersion = "4.2",
            androidApiLevel = 31,
            romFamily = RomFamily.HARMONY_OS,
            romVersion = "4.2",
            googleEnvironment = GoogleEnvironment.UNKNOWN,
            installationCapability = InstallationCapability.LEGACY_HARMONY_COMPATIBLE,
            validationLevel = GlobalValidationLevel.PROBABLE,
            evidence = emptyList(),
        ),
        compatibilityPlan = CompatibilityPlan(
            deviceCategory = DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT,
            planId = CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN,
            status = CompatibilityPlanStatus.CONFIGURATION_REQUIRED,
            requiredComponents = emptyList(),
            installationOrder = emptyList(),
            evidence = emptyList(),
            warnings = emptyList(),
            confidence = DetectionConfidence.HIGH,
        ),
    )

    private companion object {
        const val NOW = 1_789_896_000_000L
        const val GMS_PACKAGE = "com.google.android.gms"
        const val VENDING_PACKAGE = "com.android.vending"
        const val OFFICIAL_SIGNER =
            "9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165"
        const val GOOGLE_SIGNER =
            "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
        const val RANDOM_SIGNER =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val OFFICIAL_HASHES = mapOf(
            GMS_PACKAGE to
                "a44ce933e2336d3340eb82ad3bb28bba03bc56a7b3cf3c98250a225c55b572de",
            VENDING_PACKAGE to
                "c1aa0c8854fcdac31d23d54e1ea62daedff6b7a6405a2f5ff5351c2dde8f113d",
        )
    }
}
