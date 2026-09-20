package com.example.globalcompat.audit

import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.CompatibilityValidationStatus
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAuditEngineTest {
    @Test
    fun `official assets resolve by exact catalog filename`() {
        val report = engine().audit(RELEASE_TAG, outputDirectory())

        assertEquals(AuditStatus.PASS, report.status)
        assertEquals(listOf(ASSET_GMS, ASSET_VENDING), report.artifacts.map { it.artifactFilename })
        assertEquals(listOf(1001L, 1002L), report.artifacts.map { it.githubAssetId })
    }

    @Test
    fun `missing filename fails closed`() {
        val report = engine(release = release().copy(assets = release().assets.drop(1)))
            .audit(RELEASE_TAG, outputDirectory())

        assertFailure(report, "ASSET_MATCH_COUNT_INVALID")
    }

    @Test
    fun `asset metadata mismatch fails closed`() {
        val mismatched = release().copy(
            assets = release().assets.map {
                if (it.name == ASSET_GMS) it.copy(contentType = "application/octet-stream") else it
            },
        )

        val report = engine(release = mismatched).audit(RELEASE_TAG, outputDirectory())

        assertFailure(report, "ASSET_METADATA_MISMATCH")
    }

    @Test
    fun `GitHub hash mismatch fails closed`() {
        val mismatched = release().copy(
            assets = release().assets.map {
                if (it.name == ASSET_GMS) it.copy(digest = "sha256:${"0".repeat(64)}") else it
            },
        )

        val report = engine(release = mismatched).audit(RELEASE_TAG, outputDirectory())

        assertFailure(report, "SHA256_MISMATCH")
    }

    @Test
    fun `APK signature verification failure fails closed`() {
        val report = engine(inspector = FakeApkInspector(signatureVerified = false))
            .audit(RELEASE_TAG, outputDirectory())

        assertFailure(report, "APK_SIGNATURE_INVALID")
    }

    @Test
    fun `package name mismatch fails closed`() {
        val report = engine(inspector = FakeApkInspector(packageOverride = "wrong.package"))
            .audit(RELEASE_TAG, outputDirectory())

        assertFailure(report, "PACKAGE_NAME_MISMATCH")
    }

    @Test
    fun `completed audit never changes compatibility validation`() {
        val catalogRelease = BuiltInComponentCatalog.catalog.releases.single()
        val releaseStatusBefore = catalogRelease.compatibilityStatus
        val artifactStatusesBefore = catalogRelease.artifacts.map { it.compatibilityStatus }

        val report = engine().audit(RELEASE_TAG, outputDirectory())

        assertEquals(AuditStatus.PASS, report.status)
        assertEquals(CompatibilityValidationStatus.CANDIDATE, releaseStatusBefore)
        assertTrue(artifactStatusesBefore.all { it == CompatibilityValidationStatus.UNTESTED })
        assertEquals(releaseStatusBefore, catalogRelease.compatibilityStatus)
        assertEquals(artifactStatusesBefore, catalogRelease.artifacts.map { it.compatibilityStatus })
    }

    private fun engine(
        release: GitHubReleaseMetadata = release(),
        inspector: ApkInspector = FakeApkInspector(),
    ) = CatalogAuditEngine(
        github = FakeGitHubClient(release),
        apkInspector = inspector,
        clock = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC),
    )

    private fun release(): GitHubReleaseMetadata {
        val assets = listOf(
            asset(1001L, ASSET_GMS, GMS_BYTES),
            asset(1002L, ASSET_VENDING, VENDING_BYTES),
        )
        return GitHubReleaseMetadata(RELEASE_TAG, RELEASE_URL, assets)
    }

    private fun asset(id: Long, filename: String, bytes: ByteArray) = GitHubAssetMetadata(
        id = id,
        name = filename,
        contentType = "application/vnd.android.package-archive",
        state = "uploaded",
        size = bytes.size.toLong(),
        digest = "sha256:${sha256(bytes)}",
        downloadUrl = "$DOWNLOAD_BASE/$filename",
    )

    private fun assertFailure(report: CatalogAuditReport, code: String) {
        assertEquals(AuditStatus.FAIL, report.status)
        assertEquals(code, report.failures.single().code)
    }

    private fun outputDirectory(): Path = Files.createTempDirectory("catalog-audit-test-")

    private class FakeGitHubClient(
        private val release: GitHubReleaseMetadata,
    ) : GitHubReleaseClient {
        override fun getRelease(releaseTag: String): GitHubReleaseMetadata = release

        override fun download(asset: GitHubAssetMetadata, destination: Path) {
            Files.createDirectories(destination.parent)
            val bytes = when (asset.name) {
                ASSET_GMS -> GMS_BYTES
                ASSET_VENDING -> VENDING_BYTES
                else -> error("Unexpected asset ${asset.name}")
            }
            Files.write(destination, bytes)
        }
    }

    private class FakeApkInspector(
        private val signatureVerified: Boolean = true,
        private val packageOverride: String? = null,
    ) : ApkInspector {
        override fun inspect(apk: Path): ApkInspection {
            val isGms = apk.fileName.toString() == ASSET_GMS
            return ApkInspection(
                packageName = packageOverride ?: if (isGms) "com.google.android.gms" else "com.android.vending",
                versionName = if (isGms) "0.3.16.252432" else "0.1.0",
                versionCode = if (isGms) "250932032" else "84022632",
                signingCertificateSha256 = listOf("certificate-sha256"),
                signatureVerified = signatureVerified,
                signatureVerificationOutput = if (signatureVerified) "Verified" else "Failed",
            )
        }
    }

    private companion object {
        const val RELEASE_TAG = "v0.3.16.252432"
        const val RELEASE_URL =
            "https://github.com/microg/GmsCore/releases/tag/v0.3.16.252432"
        const val DOWNLOAD_BASE =
            "https://github.com/microg/GmsCore/releases/download/v0.3.16.252432"
        const val ASSET_GMS = "com.google.android.gms-250932032-hw.apk"
        const val ASSET_VENDING = "com.android.vending-84022632-hw.apk"
        val GMS_BYTES = "gms-apk-content".toByteArray()
        val VENDING_BYTES = "vending-apk-content".toByteArray()

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
