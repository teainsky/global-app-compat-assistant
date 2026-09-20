package com.example.globalcompat.baseline

import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.data.AndroidPlatform
import com.example.globalcompat.data.CompatibilityPlan
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.CompatibilityPlanStatus
import com.example.globalcompat.data.CompatibilityLayerAssessment
import com.example.globalcompat.data.DetectionConfidence
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.data.DeviceIdentity
import com.example.globalcompat.data.EnvironmentReport
import com.example.globalcompat.data.GoogleCompatibilityLayerStatus
import com.example.globalcompat.data.RomFamily
import com.example.globalcompat.data.RomIdentification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBaselineTest {
    private val matcher = OfficialComponentMatcher(BuiltInComponentCatalog.catalog)
    private val factory = DeviceBaselineReportFactory()

    @Test
    fun `official versions and signers fully match`() {
        val comparisons = matcher.compare(officialPair())

        assertEquals(2, comparisons.size)
        assertTrue(comparisons.all {
            it.status == OfficialComponentMatchStatus.OFFICIAL_METADATA_MATCH
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
    fun `different signer reports signer mismatch`() {
        val fingerprints = officialPair().map { fingerprint ->
            if (fingerprint.packageName == GMS_PACKAGE) {
                fingerprint.copy(signingCertificateSha256 = listOf("different-signer"))
            } else {
                fingerprint
            }
        }

        val comparisons = matcher.compare(fingerprints)

        assertEquals(
            OfficialComponentMatchStatus.SIGNER_MISMATCH,
            comparisons.single { it.fingerprint.packageName == GMS_PACKAGE }.status,
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
            OfficialComponentMatchStatus.OFFICIAL_METADATA_MATCH,
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
                fingerprint.copy(signingCertificateSha256 = listOf("different-signer"))
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
            matcher.compare(officialPair()),
            userValidation,
            NOW,
        )

        assertNull(report.deviceValidationRecord)
    }

    @Test
    fun `official pair and successful confirmations create local validation record`() {
        val report = factory.create(
            environment(),
            matcher.compare(officialPair()),
            allYes(),
            NOW,
        )

        val record = requireNotNull(report.deviceValidationRecord)
        assertEquals(NOW, record.validatedAtEpochMillis)
        assertEquals(listOf(VENDING_PACKAGE, GMS_PACKAGE).sorted(), record.matchedPackages)
    }

    @Test
    fun `baseline JSON excludes device unique identifiers`() {
        val report = factory.create(
            environment(),
            matcher.compare(officialPair()),
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
        signingCertificateSha256 = listOf(OFFICIAL_SIGNER),
        installSource = "com.huawei.appmarket",
        readStatus = ComponentFingerprintReadStatus.READABLE,
    )

    private fun missingFingerprint(packageName: String) = InstalledComponentFingerprint(
        installed = false,
        enabled = null,
        packageName = packageName,
        versionCode = null,
        versionName = null,
        signingCertificateSha256 = emptyList(),
        installSource = null,
        readStatus = ComponentFingerprintReadStatus.NOT_INSTALLED,
    )

    private fun allYes() = UserFunctionalValidation(
        googleAccountLogin = UserValidationAnswer.YES,
        chatGptLoginAndUse = UserValidationAnswer.YES,
        chromeGoogleLogin = UserValidationAnswer.YES,
    )

    private fun environment() = EnvironmentReport(
        schemaVersion = 2,
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
    }
}
