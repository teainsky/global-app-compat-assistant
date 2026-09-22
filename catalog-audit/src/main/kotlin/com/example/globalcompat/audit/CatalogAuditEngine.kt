package com.example.globalcompat.audit

import com.example.globalcompat.catalog.ArtifactDescriptor
import com.example.globalcompat.catalog.ArtifactIntegrityStatus
import com.example.globalcompat.catalog.ArtifactSourceRecord
import com.example.globalcompat.catalog.ComponentCatalog
import com.example.globalcompat.catalog.ComponentArtifact
import com.example.globalcompat.catalog.ComponentSourceType
import com.example.globalcompat.catalog.SourceAvailabilityStatus
import com.example.globalcompat.catalog.RuntimeTrustedCatalogRepository
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

class CatalogAuditEngine(
    private val sourceResolver: ArtifactSourceResolver,
    private val downloader: OfficialArtifactDownloader,
    private val apkInspector: ApkInspector,
    private val clock: Clock = Clock.systemUTC(),
    private val catalog: ComponentCatalog? =
        RuntimeTrustedCatalogRepository.instance.currentSnapshot()?.catalog,
) {
    fun audit(releaseTag: String, outputDirectory: Path): CatalogAuditReport {
        val auditedAt = Instant.now(clock).toString()
        val catalogRelease = catalog?.releases
            .orEmpty()
            .singleOrNull { it.releaseTag == releaseTag }
            ?: return failedReport(
                releaseTag,
                auditedAt,
                emptyList(),
                "CATALOG_RELEASE_NOT_FOUND",
                "Catalog release tag not found: $releaseTag",
            )
        val descriptors = try {
            catalogRelease.artifacts.map(::requireDescriptor)
        } catch (failure: AuditClosedException) {
            return failedReport(
                releaseTag,
                auditedAt,
                emptyList(),
                failure.code,
                failure.message.orEmpty(),
            )
        }
        val sourceRecords = try {
            sourceResolver.resolve(releaseTag, descriptors)
        } catch (failure: Exception) {
            return failedReport(
                releaseTag,
                auditedAt,
                emptyList(),
                "SOURCE_RESOLUTION_ERROR",
                failure.message ?: failure.javaClass.simpleName,
            )
        }
        val results = mutableListOf<ArtifactAuditResult>()
        val failures = mutableListOf<AuditFailure>()
        val warnings = sourceRecords.flatMap { record ->
            record.warningCodes.map { code ->
                AuditWarning(
                    code = code,
                    componentId = record.componentId,
                    message = "$code for ${record.componentId}; GitHub API asset metadata remains authoritative",
                )
            }
        }.distinct()
        val downloadsDirectory = outputDirectory.resolve("downloads")

        descriptors.forEach { descriptor ->
            val records = sourceRecords.filter { it.componentId == descriptor.componentId }
            val available = records
                .filter { it.availabilityStatus == SourceAvailabilityStatus.AVAILABLE }
                .sortedBy { SOURCE_PRIORITY.indexOf(it.sourceType) }
            val selected = available.firstOrNull { record ->
                record.sourceType == ComponentSourceType.OFFICIAL_MICROG_GITHUB &&
                    record.sourceAssetId == descriptor.githubAssetId &&
                    record.observedFilename == descriptor.artifactFilename &&
                    record.downloadUrl?.let { url ->
                        OfficialSourceUrlPolicy.isAllowed(record.sourceType, url)
                    } == true
            }
            if (selected == null) {
                val invalidAvailable = available.firstOrNull()
                failures += if (invalidAvailable != null) {
                    AuditFailure(
                        "SOURCE_METADATA_MISMATCH",
                        "No valid official source record for ${descriptor.artifactFilename}",
                    )
                } else {
                    AuditFailure(
                        "NO_OFFICIAL_BINARY_AVAILABLE",
                        "No official source currently exposes ${descriptor.artifactFilename}",
                    )
                }
                return@forEach
            }
            try {
                Files.createDirectories(downloadsDirectory)
                val destination = downloadsDirectory.resolve(descriptor.artifactFilename)
                downloader.download(selected, destination)
                try {
                    results += auditDownloadedArtifact(
                        releaseTag = releaseTag,
                        descriptor = descriptor,
                        sourceRecord = selected,
                        destination = destination,
                        auditedAt = auditedAt,
                    )
                } finally {
                    Files.deleteIfExists(destination)
                }
            } catch (failure: AuditClosedException) {
                failures += AuditFailure(failure.code, failure.message.orEmpty())
            } catch (failure: Exception) {
                failures += AuditFailure(
                    "AUDIT_EXECUTION_ERROR",
                    failure.message ?: failure.javaClass.simpleName,
                )
            }
        }
        runCatching { Files.deleteIfExists(downloadsDirectory) }

        return CatalogAuditReport(
            releaseTag = releaseTag,
            status = if (failures.isEmpty() && results.size == descriptors.size) {
                AuditStatus.PASS
            } else {
                AuditStatus.FAIL
            },
            sourceRecords = sourceRecords,
            artifacts = results,
            warnings = warnings,
            failures = failures,
            auditedAt = auditedAt,
        )
    }

    private fun auditDownloadedArtifact(
        releaseTag: String,
        descriptor: ArtifactDescriptor,
        sourceRecord: ArtifactSourceRecord,
        destination: Path,
        auditedAt: String,
    ): ArtifactAuditResult {
        if (!Files.isRegularFile(destination)) {
            fail("DOWNLOAD_MISSING", "Download did not create ${descriptor.artifactFilename}")
        }
        val downloadedSize = Files.size(destination)
        sourceRecord.expectedSize?.let { expectedSize ->
            if (downloadedSize != expectedSize) {
                fail(
                    "DOWNLOADED_SIZE_MISMATCH",
                    "${descriptor.artifactFilename}: downloaded $downloadedSize bytes, source reports $expectedSize",
                )
            }
        }
        val sha256 = sha256(destination)
        val githubDigestMatches = validateSourceDigest(sourceRecord, sha256)
        val inspection = apkInspector.inspect(destination)
        if (!inspection.signatureVerified) {
            fail("APK_SIGNATURE_INVALID", "apksigner rejected ${descriptor.artifactFilename}")
        }
        if (inspection.signingCertificateSha256.isEmpty()) {
            fail(
                "SIGNING_CERTIFICATE_MISSING",
                "No signer certificate digest for ${descriptor.artifactFilename}",
            )
        }
        if (inspection.packageName != descriptor.packageName) {
            fail(
                "PACKAGE_NAME_MISMATCH",
                "${descriptor.artifactFilename}: ${inspection.packageName} != ${descriptor.packageName}",
            )
        }
        if (inspection.versionCode != descriptor.artifactVersionCode) {
            fail(
                "VERSION_CODE_MISMATCH",
                "${descriptor.artifactFilename}: ${inspection.versionCode} != ${descriptor.artifactVersionCode}",
            )
        }
        return ArtifactAuditResult(
            componentId = descriptor.componentId,
            releaseTag = releaseTag,
            sourceType = sourceRecord.sourceType.name,
            filename = descriptor.artifactFilename,
            assetId = requireNotNull(sourceRecord.sourceAssetId),
            assetSize = downloadedSize,
            sourceUrl = requireNotNull(sourceRecord.downloadUrl),
            githubDigest = sourceRecord.sourceDigest,
            githubDigestMatches = githubDigestMatches,
            locallyCalculatedSha256 = sha256,
            packageName = inspection.packageName,
            versionName = inspection.versionName,
            versionCode = inspection.versionCode,
            signingCertificateSha256 = inspection.signingCertificateSha256,
            apksignerVerificationResult = "PASS",
            artifactIntegrityStatus = ArtifactIntegrityStatus.SIGNATURE_VERIFIED,
            compatibilityValidationStatus = descriptor.compatibilityStatus,
            auditedAt = auditedAt,
        )
    }

    private fun requireDescriptor(artifact: ComponentArtifact): ArtifactDescriptor {
        val filename = artifact.artifactFilename
        val versionCode = artifact.artifactVersionCode
        val githubAssetId = artifact.githubAssetId
        if (filename.isNullOrBlank() || versionCode.isNullOrBlank() || githubAssetId == null) {
            fail("CATALOG_METADATA_MISSING", "Explicit artifact metadata is required")
        }
        if (Path.of(filename).fileName.toString() != filename) {
            fail("CATALOG_FILENAME_INVALID", "Artifact filename must not contain a path")
        }
        return ArtifactDescriptor(
            componentId = artifact.componentId,
            packageName = artifact.packageName,
            releaseVersion = artifact.releaseVersion,
            artifactFilename = filename,
            artifactVersionCode = versionCode,
            variant = artifact.variant,
            githubAssetId = githubAssetId,
            compatibilityStatus = artifact.compatibilityStatus,
        )
    }

    private fun validateSourceDigest(record: ArtifactSourceRecord, localSha256: String): Boolean? {
        val digest = record.sourceDigest ?: return null
        val parts = digest.split(":", limit = 2)
        if (parts.size != 2 || parts[0] != "sha256") {
            fail("ASSET_DIGEST_FORMAT_UNSUPPORTED", "Unsupported source digest: $digest")
        }
        if (!parts[1].equals(localSha256, ignoreCase = true)) {
            fail("SHA256_MISMATCH", "${record.observedFilename}: local SHA-256 differs from source digest")
        }
        return true
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun failedReport(
        releaseTag: String,
        auditedAt: String,
        sourceRecords: List<ArtifactSourceRecord>,
        code: String,
        message: String,
    ) = CatalogAuditReport(
        releaseTag = releaseTag,
        status = AuditStatus.FAIL,
        sourceRecords = sourceRecords,
        artifacts = emptyList(),
        warnings = emptyList(),
        failures = listOf(AuditFailure(code, message)),
        auditedAt = auditedAt,
    )

    private fun fail(code: String, message: String): Nothing =
        throw AuditClosedException(code, message)

    private companion object {
        val SOURCE_PRIORITY = listOf(
            ComponentSourceType.OFFICIAL_MICROG_GITHUB,
            ComponentSourceType.OFFICIAL_MICROG_DOWNLOAD_PAGE,
            ComponentSourceType.OFFICIAL_HUAWEI_APPGALLERY,
        )
    }
}

private class AuditClosedException(
    val code: String,
    message: String,
) : RuntimeException(message)
