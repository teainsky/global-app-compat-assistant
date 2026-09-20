package com.example.globalcompat.audit

import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.InstalledArtifactSignatureStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HostDeviceArtifactAuditTest {
    private val evaluator = HostArtifactEvidenceEvaluator()
    private val gmsCore = BuiltInComponentCatalog.catalog.releases
        .flatMap { it.artifacts }
        .single { it.packageName == GMS_PACKAGE }

    @Test
    fun `host hash and signer exactly matching audited artifact is actual artifact match`() {
        val status = evaluator.evaluate(
            artifact = gmsCore,
            evidence = evidence(gmsCore.sha256.orEmpty()),
        )

        assertEquals(InstalledArtifactSignatureStatus.ACTUAL_ARTIFACT_MATCH, status)
    }

    @Test
    fun `same version with different hash cannot become actual artifact match`() {
        val status = evaluator.evaluate(
            artifact = gmsCore,
            evidence = evidence(DIFFERENT_SHA256),
        )

        assertNotEquals(InstalledArtifactSignatureStatus.ACTUAL_ARTIFACT_MATCH, status)
        assertEquals(InstalledArtifactSignatureStatus.UNKNOWN, status)
    }

    @Test
    fun `host audit deletes temporary pulled APKs`() {
        val temporaryDirectory = Files.createTempDirectory("device-audit-test-")
        val bridge = object : HostDeviceBridge {
            override fun installedApkPaths(packageName: String) = listOf("/data/app/$packageName/base.apk")

            override fun pull(remotePath: String, destination: Path) {
                Files.write(destination, "not-an-apk".toByteArray())
            }
        }
        val inspector = object : ApkInspector {
            override fun inspect(apk: Path): ApkInspection {
                val packageName = if (apk.fileName.toString().startsWith(GMS_PACKAGE)) {
                    GMS_PACKAGE
                } else {
                    VENDING_PACKAGE
                }
                return if (packageName == GMS_PACKAGE) {
                    ApkInspection(
                        packageName = packageName,
                        versionName = "0.3.16.252432-hw",
                        versionCode = "252432032",
                        signingCertificateSha256 = listOf(OFFICIAL_SIGNER),
                        signatureVerified = true,
                        signatureVerificationOutput = "verified",
                    )
                } else {
                    ApkInspection(
                        packageName = packageName,
                        versionName = "0.3.16.40226-hw",
                        versionCode = "84022632",
                        signingCertificateSha256 = listOf(OFFICIAL_SIGNER),
                        signatureVerified = true,
                        signatureVerificationOutput = "verified",
                    )
                }
            }
        }

        HostDeviceArtifactAuditor(
            bridge = bridge,
            apkInspector = inspector,
            createTemporaryDirectory = { temporaryDirectory },
        ).audit()

        assertFalse(temporaryDirectory.exists())
    }

    private fun evidence(sha256: String) = HostArtifactEvidence(
        packageName = GMS_PACKAGE,
        versionCode = "252432032",
        versionName = "0.3.16.252432-hw",
        locallyCalculatedSha256 = sha256,
        signingCertificateSha256 = listOf(OFFICIAL_SIGNER),
        apkSignatureVerified = true,
    )

    private companion object {
        const val GMS_PACKAGE = "com.google.android.gms"
        const val VENDING_PACKAGE = "com.android.vending"
        const val OFFICIAL_SIGNER =
            "9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165"
        const val DIFFERENT_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
