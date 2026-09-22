package com.example.globalcompat.artifact

import com.example.globalcompat.baseline.OfficialComponentMatcher
import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.asTestSnapshot
import com.example.globalcompat.validation.DeviceValidationEvidenceLevel
import com.example.globalcompat.validation.ValidationDeviceProfile
import com.example.globalcompat.validation.ValidationEvidenceSource
import com.example.globalcompat.validation.ValidationSystemProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceArtifactAuditorTest {
    private val catalog = BuiltInComponentCatalog.catalog
    private val artifacts = catalog.releases.single().artifacts.associateBy { it.packageName }
    private val request = OnDeviceArtifactAuditRequest(
        deviceProfile = ValidationDeviceProfile("Huawei", "Pura 70 Pro+"),
        systemProfile = ValidationSystemProfile(
            harmonyOsVersion = "4.2",
            androidVersion = "12",
            androidApiLevel = 31,
            romFamily = "HARMONY_OS",
            romVersion = "4.2",
        ),
    )

    @Test
    fun `two readable monolithic APKs matching catalog attain artifact verified`() {
        val report = auditor().audit(request)

        assertEquals(
            OnDeviceArtifactAuditAvailability.ON_DEVICE_ARTIFACT_AUDIT_SUPPORTED,
            report.availability,
        )
        assertTrue(report.components.all { it.officialArtifactMatched })
        assertEquals(DeviceValidationEvidenceLevel.ARTIFACT_VERIFIED, report.attainedEvidenceLevel)
        assertEquals(
            ValidationEvidenceSource.ON_DEVICE_READ_ONLY_AUDIT,
            report.artifactEvidence.source,
        )
    }

    @Test
    fun `one unreadable APK path is partially supported and cannot verify pair`() {
        val report = auditor(
            unreadablePackages = setOf("com.google.android.gms"),
        ).audit(request)

        assertEquals(OnDeviceArtifactAuditAvailability.PARTIALLY_SUPPORTED, report.availability)
        assertFalse(
            report.components.single { it.packageName == "com.google.android.gms" }
                .officialArtifactMatched,
        )
        assertNull(report.attainedEvidenceLevel)
    }

    @Test
    fun `both APK files inaccessible fail closed without guessed hashes`() {
        val report = auditor(
            unreadablePackages = artifacts.keys,
        ).audit(request)

        assertEquals(OnDeviceArtifactAuditAvailability.NOT_ACCESSIBLE, report.availability)
        assertTrue(report.components.all { it.installedApkSha256 == null })
        assertNull(report.attainedEvidenceLevel)
    }

    @Test
    fun `split APK layout remains partial even when base hash matches`() {
        val report = auditor(
            splitPackages = setOf("com.android.vending"),
        ).audit(request)

        assertEquals(OnDeviceArtifactAuditAvailability.PARTIALLY_SUPPORTED, report.availability)
        val companion = report.components.single {
            it.packageName == "com.android.vending"
        }
        assertEquals(OnDeviceArtifactReadStatus.SPLIT_APK_LAYOUT_UNSUPPORTED, companion.readStatus)
        assertFalse(companion.officialArtifactMatched)
    }

    @Test
    fun `readable APK with different bytes never matches official artifact`() {
        val report = auditor(
            mismatchedPackages = setOf("com.google.android.gms"),
        ).audit(request)

        assertEquals(
            OnDeviceArtifactAuditAvailability.ON_DEVICE_ARTIFACT_AUDIT_SUPPORTED,
            report.availability,
        )
        assertFalse(
            report.components.single { it.packageName == "com.google.android.gms" }
                .officialArtifactMatched,
        )
        assertNull(report.attainedEvidenceLevel)
    }

    private fun auditor(
        unreadablePackages: Set<String> = emptySet(),
        splitPackages: Set<String> = emptySet(),
        mismatchedPackages: Set<String> = emptySet(),
    ): OnDeviceArtifactAuditor {
        val lookup = InstalledApkPathLookup { packageName ->
            val artifact = artifacts.getValue(packageName)
            InstalledApkLookupResult(
                InstalledApkLookupStatus.INSTALLED,
                InstalledApkDescriptor(
                    packageName = packageName,
                    versionCode = requireNotNull(artifact.artifactVersionCode),
                    versionName = artifact.artifactVersionName,
                    reportedSigningCertificateSha256 = listOf(
                        OfficialComponentMatcher.GOOGLE_PRIVILEGED_SIGNER_SHA256,
                    ),
                    baseApkPath = "/data/app/$packageName/base.apk",
                    splitApkPaths = if (packageName in splitPackages) {
                        listOf("/data/app/$packageName/split_config.apk")
                    } else {
                        emptyList()
                    },
                ),
            )
        }
        val digestReader = ApkByteDigestReader { path ->
            val packageName = artifacts.keys.single { it in path }
            when {
                packageName in unreadablePackages ->
                    ApkByteDigestResult(false, null, "AccessDenied")
                packageName in mismatchedPackages ->
                    ApkByteDigestResult(true, RANDOM_SHA256, null)
                else -> ApkByteDigestResult(
                    true,
                    requireNotNull(artifacts.getValue(packageName).sha256),
                    null,
                )
            }
        }
        return OnDeviceArtifactAuditor(
            catalogSnapshot = catalog.asTestSnapshot(),
            installedApkLookup = lookup,
            digestReader = digestReader,
            clock = { 1_700_000_000_000L },
        )
    }

    private companion object {
        const val RANDOM_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
