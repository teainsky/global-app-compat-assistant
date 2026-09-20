package com.example.globalcompat.audit

import com.example.globalcompat.catalog.ArtifactDescriptor
import com.example.globalcompat.catalog.ArtifactSourceRecord
import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.CompatibilityValidationStatus
import com.example.globalcompat.catalog.ComponentSourceType
import com.example.globalcompat.catalog.ComponentVariant
import com.example.globalcompat.catalog.SourceAvailabilityStatus
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAuditEngineTest {
    @Test
    fun `release notes declaration does not make missing GitHub asset available`() {
        val resolver = OfficialArtifactSourceResolver(
            github = FakeGitHubClient(
                release = githubRelease(assets = listOf(githubAsset(VENDING_ASSET_ID, ASSET_VENDING, VENDING_BYTES))),
            ),
            pageClient = FakePageClient(downloadPageBody = RELEASE_NOTES),
        )

        val records = resolver.resolve(RELEASE_TAG, descriptors())
        val gmsGitHub = records.single {
            it.componentId == GMS_ID && it.sourceType == ComponentSourceType.OFFICIAL_MICROG_GITHUB
        }
        val gmsDownloadPage = records.single {
            it.componentId == GMS_ID &&
                it.sourceType == ComponentSourceType.OFFICIAL_MICROG_DOWNLOAD_PAGE
        }

        assertEquals(SourceAvailabilityStatus.MISSING, gmsGitHub.availabilityStatus)
        assertTrue(gmsGitHub.evidence.any { it.contains("Release notes declare") })
        assertEquals(SourceAvailabilityStatus.METADATA_ONLY, gmsDownloadPage.availabilityStatus)
        assertEquals(null, gmsDownloadPage.downloadUrl)
    }

    @Test
    fun `Companion is available on GitHub while GmsCore is missing`() {
        val resolver = OfficialArtifactSourceResolver(
            github = FakeGitHubClient(
                release = githubRelease(assets = listOf(githubAsset(VENDING_ASSET_ID, ASSET_VENDING, VENDING_BYTES))),
            ),
            pageClient = FakePageClient(downloadPageBody = RELEASE_NOTES),
        )

        val githubRecords = resolver.resolve(RELEASE_TAG, descriptors())
            .filter { it.sourceType == ComponentSourceType.OFFICIAL_MICROG_GITHUB }

        assertEquals(SourceAvailabilityStatus.MISSING, githubRecords.single { it.componentId == GMS_ID }.availabilityStatus)
        assertEquals(SourceAvailabilityStatus.AVAILABLE, githubRecords.single { it.componentId == VENDING_ID }.availabilityStatus)
    }

    @Test
    fun `one missing official source does not mean component is unavailable everywhere`() {
        val records = sourceRecords(
            gmsGitHub = SourceAvailabilityStatus.MISSING,
            vendingGitHub = SourceAvailabilityStatus.AVAILABLE,
        ).map { record ->
            if (record.componentId == GMS_ID &&
                record.sourceType == ComponentSourceType.OFFICIAL_HUAWEI_APPGALLERY
            ) {
                availableRecord(
                    componentId = GMS_ID,
                    filename = ASSET_GMS,
                    bytes = GMS_BYTES,
                    sourceType = ComponentSourceType.OFFICIAL_HUAWEI_APPGALLERY,
                    downloadUrl = "https://appgallery.huawei.com/app/$GMS_ID",
                )
            } else {
                record
            }
        }

        val report = engine(records = records).audit(RELEASE_TAG, outputDirectory())

        assertEquals(AuditStatus.PASS, report.status)
        assertEquals(
            ComponentSourceType.OFFICIAL_HUAWEI_APPGALLERY.name,
            report.artifacts.single { it.packageName == "com.google.android.gms" }.sourceType,
        )
    }

    @Test
    fun `all official sources unresolved fails closed`() {
        val records = descriptors().flatMap { descriptor ->
            ComponentSourceType.entries.map { source ->
                unresolvedRecord(descriptor.componentId, source)
            }
        }
        val downloader = FakeDownloader()

        val report = engine(records = records, downloader = downloader)
            .audit(RELEASE_TAG, outputDirectory())

        assertEquals(AuditStatus.FAIL, report.status)
        assertEquals(2, report.failures.count { it.code == "NO_OFFICIAL_BINARY_AVAILABLE" })
        assertTrue(downloader.downloadedUrls.isEmpty())
    }

    @Test
    fun `third party URL cannot be used as fallback`() {
        val records = sourceRecords().map { record ->
            if (record.componentId == GMS_ID &&
                record.sourceType == ComponentSourceType.OFFICIAL_MICROG_GITHUB
            ) {
                record.copy(downloadUrl = "https://apkpure.com/$ASSET_GMS")
            } else {
                record
            }
        }
        val downloader = FakeDownloader()

        val report = engine(records = records, downloader = downloader)
            .audit(RELEASE_TAG, outputDirectory())

        assertEquals(AuditStatus.FAIL, report.status)
        assertTrue(report.failures.any { it.code == "SOURCE_METADATA_MISMATCH" })
        assertFalse(downloader.downloadedUrls.any { it.contains("apkpure") })
    }

    @Test
    fun `official binary records audit by exact descriptor filename`() {
        val report = engine().audit(RELEASE_TAG, outputDirectory())

        assertEquals(AuditStatus.PASS, report.status)
        assertEquals(listOf(ASSET_GMS, ASSET_VENDING), report.artifacts.map { it.artifactFilename })
        assertEquals(listOf(GMS_ID, VENDING_ID), report.artifacts.map { it.sourceAssetId })
    }

    @Test
    fun `missing filename across sources fails closed`() {
        val report = engine(
            records = sourceRecords(gmsGitHub = SourceAvailabilityStatus.MISSING),
        ).audit(RELEASE_TAG, outputDirectory())

        assertEquals(AuditStatus.FAIL, report.status)
        assertTrue(report.failures.any { it.code == "NO_OFFICIAL_BINARY_AVAILABLE" })
    }

    @Test
    fun `source metadata mismatch fails closed`() {
        val records = sourceRecords().map { record ->
            if (record.componentId == GMS_ID && record.availabilityStatus == SourceAvailabilityStatus.AVAILABLE) {
                record.copy(observedFilename = "wrong.apk")
            } else {
                record
            }
        }

        val report = engine(records = records).audit(RELEASE_TAG, outputDirectory())

        assertEquals(AuditStatus.FAIL, report.status)
        assertTrue(report.failures.any { it.code == "SOURCE_METADATA_MISMATCH" })
    }

    @Test
    fun `source hash mismatch fails closed`() {
        val records = sourceRecords().map { record ->
            if (record.componentId == GMS_ID && record.availabilityStatus == SourceAvailabilityStatus.AVAILABLE) {
                record.copy(sourceDigest = "sha256:${"0".repeat(64)}")
            } else {
                record
            }
        }

        val report = engine(records = records).audit(RELEASE_TAG, outputDirectory())

        assertTrue(report.failures.any { it.code == "SHA256_MISMATCH" })
    }

    @Test
    fun `APK signature verification failure fails closed`() {
        val report = engine(inspector = FakeApkInspector(signatureVerified = false))
            .audit(RELEASE_TAG, outputDirectory())

        assertTrue(report.failures.any { it.code == "APK_SIGNATURE_INVALID" })
    }

    @Test
    fun `package name mismatch fails closed`() {
        val report = engine(inspector = FakeApkInspector(packageOverride = "wrong.package"))
            .audit(RELEASE_TAG, outputDirectory())

        assertTrue(report.failures.any { it.code == "PACKAGE_NAME_MISMATCH" })
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
        records: List<ArtifactSourceRecord> = sourceRecords(),
        downloader: FakeDownloader = FakeDownloader(),
        inspector: ApkInspector = FakeApkInspector(),
    ) = CatalogAuditEngine(
        sourceResolver = FakeSourceResolver(records),
        downloader = downloader,
        apkInspector = inspector,
        clock = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC),
    )

    private fun sourceRecords(
        gmsGitHub: SourceAvailabilityStatus = SourceAvailabilityStatus.AVAILABLE,
        vendingGitHub: SourceAvailabilityStatus = SourceAvailabilityStatus.AVAILABLE,
    ): List<ArtifactSourceRecord> = listOf(
        recordForStatus(GMS_ID, ASSET_GMS, GMS_BYTES, gmsGitHub),
        unresolvedRecord(GMS_ID, ComponentSourceType.OFFICIAL_MICROG_DOWNLOAD_PAGE),
        unresolvedRecord(GMS_ID, ComponentSourceType.OFFICIAL_HUAWEI_APPGALLERY),
        recordForStatus(VENDING_ID, ASSET_VENDING, VENDING_BYTES, vendingGitHub),
        unresolvedRecord(VENDING_ID, ComponentSourceType.OFFICIAL_MICROG_DOWNLOAD_PAGE),
        unresolvedRecord(VENDING_ID, ComponentSourceType.OFFICIAL_HUAWEI_APPGALLERY),
    )

    private fun recordForStatus(
        componentId: String,
        filename: String,
        bytes: ByteArray,
        status: SourceAvailabilityStatus,
    ): ArtifactSourceRecord = if (status == SourceAvailabilityStatus.AVAILABLE) {
        availableRecord(componentId, filename, bytes)
    } else {
        ArtifactSourceRecord(
            componentId = componentId,
            sourceType = ComponentSourceType.OFFICIAL_MICROG_GITHUB,
            availabilityStatus = status,
            sourcePageUrl = RELEASE_URL,
            observedFilename = filename,
        )
    }

    private fun availableRecord(
        componentId: String,
        filename: String,
        bytes: ByteArray,
        sourceType: ComponentSourceType = ComponentSourceType.OFFICIAL_MICROG_GITHUB,
        downloadUrl: String = "$DOWNLOAD_BASE/$filename",
    ) = ArtifactSourceRecord(
        componentId = componentId,
        sourceType = sourceType,
        availabilityStatus = SourceAvailabilityStatus.AVAILABLE,
        sourcePageUrl = RELEASE_URL,
        downloadUrl = downloadUrl,
        sourceAssetId = componentId,
        observedFilename = filename,
        expectedSize = bytes.size.toLong(),
        sourceDigest = "sha256:${sha256(bytes)}",
    )

    private fun unresolvedRecord(componentId: String, source: ComponentSourceType) =
        ArtifactSourceRecord(
            componentId = componentId,
            sourceType = source,
            availabilityStatus = SourceAvailabilityStatus.UNRESOLVED,
            sourcePageUrl = "https://example.invalid/not-used",
        )

    private fun descriptors() = listOf(
        ArtifactDescriptor(
            GMS_ID,
            "com.google.android.gms",
            "0.3.16.252432",
            ASSET_GMS,
            "250932032",
            ComponentVariant.HUAWEI_HW,
        ),
        ArtifactDescriptor(
            VENDING_ID,
            "com.android.vending",
            "0.3.16.252432",
            ASSET_VENDING,
            "84022632",
            ComponentVariant.HUAWEI_HW,
        ),
    )

    private fun githubRelease(assets: List<GitHubAssetMetadata>) = GitHubReleaseMetadata(
        releaseTag = RELEASE_TAG,
        releaseUrl = RELEASE_URL,
        releaseNotes = RELEASE_NOTES,
        assets = assets,
    )

    private fun githubAsset(id: Long, filename: String, bytes: ByteArray) = GitHubAssetMetadata(
        id = id,
        name = filename,
        contentType = "application/vnd.android.package-archive",
        state = "uploaded",
        size = bytes.size.toLong(),
        digest = "sha256:${sha256(bytes)}",
        downloadUrl = "$DOWNLOAD_BASE/$filename",
    )

    private fun outputDirectory(): Path = Files.createTempDirectory("catalog-audit-test-")

    private class FakeSourceResolver(
        private val records: List<ArtifactSourceRecord>,
    ) : ArtifactSourceResolver {
        override fun resolve(
            releaseTag: String,
            descriptors: List<ArtifactDescriptor>,
        ): List<ArtifactSourceRecord> = records
    }

    private class FakeGitHubClient(
        private val release: GitHubReleaseMetadata,
    ) : GitHubReleaseClient {
        override fun getRelease(releaseTag: String): GitHubReleaseMetadata = release
    }

    private class FakePageClient(
        private val downloadPageBody: String = "",
        private val appGalleryBody: String = "",
    ) : OfficialPageClient {
        override fun fetch(url: String): OfficialPageSnapshot = when {
            url.contains("microg.org") -> OfficialPageSnapshot(RELEASE_URL, downloadPageBody, null)
            else -> OfficialPageSnapshot(url, appGalleryBody, null)
        }
    }

    private class FakeDownloader : OfficialArtifactDownloader {
        val downloadedUrls = mutableListOf<String>()

        override fun download(record: ArtifactSourceRecord, destination: Path) {
            downloadedUrls += requireNotNull(record.downloadUrl)
            Files.createDirectories(destination.parent)
            Files.write(
                destination,
                if (destination.fileName.toString() == ASSET_GMS) GMS_BYTES else VENDING_BYTES,
            )
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
        const val GMS_ID = "microg_services_huawei_compatible"
        const val VENDING_ID = "microg_companion_huawei_compatible"
        const val ASSET_GMS = "com.google.android.gms-250932032-hw.apk"
        const val ASSET_VENDING = "com.android.vending-84022632-hw.apk"
        const val GMS_ASSET_ID = 1001L
        const val VENDING_ASSET_ID = 1002L
        const val RELEASE_NOTES =
            "Huawei: com.google.android.gms-250932032-hw.apk and com.android.vending-84022632-hw.apk"
        val GMS_BYTES = "gms-apk-content".toByteArray()
        val VENDING_BYTES = "vending-apk-content".toByteArray()

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
