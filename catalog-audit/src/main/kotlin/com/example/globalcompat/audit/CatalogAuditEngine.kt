package com.example.globalcompat.audit

import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.ComponentArtifact
import com.example.globalcompat.catalog.ComponentSourceType
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

class CatalogAuditEngine(
    private val github: GitHubReleaseClient,
    private val apkInspector: ApkInspector,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun audit(releaseTag: String, outputDirectory: Path): CatalogAuditReport {
        val auditedAt = Instant.now(clock).toString()
        val results = mutableListOf<ArtifactAuditResult>()
        return try {
            val catalogRelease = BuiltInComponentCatalog.catalog.releases
                .singleOrNull { it.releaseTag == releaseTag }
                ?: fail("CATALOG_RELEASE_NOT_FOUND", "Catalog release tag not found: $releaseTag")
            val artifacts = catalogRelease.artifacts
            if (artifacts.isEmpty()) fail("CATALOG_ARTIFACTS_EMPTY", "Catalog release has no artifacts")
            artifacts.forEach(::requireOfficialExplicitMetadata)

            val githubRelease = github.getRelease(releaseTag)
            if (githubRelease.releaseTag != releaseTag) {
                fail("RELEASE_TAG_MISMATCH", "GitHub tag ${githubRelease.releaseTag} != $releaseTag")
            }
            val catalogReleaseUrls = artifacts.map { it.sourceReleaseUrl }.toSet()
            if (catalogReleaseUrls != setOf(githubRelease.releaseUrl)) {
                fail("RELEASE_URL_MISMATCH", "Catalog source release URL does not match GitHub metadata")
            }

            val resolvedAssets = artifacts.map { artifact ->
                val filename = artifact.artifactFilename!!
                val matches = githubRelease.assets.filter { it.name == filename }
                if (matches.size != 1) {
                    fail(
                        "ASSET_MATCH_COUNT_INVALID",
                        "Expected exactly one official asset named $filename, found ${matches.size}",
                    )
                }
                artifact to matches.single().also { validateAssetMetadata(releaseTag, it) }
            }

            val downloadsDirectory = outputDirectory.resolve("downloads")
            Files.createDirectories(downloadsDirectory)
            resolvedAssets.forEach { (artifact, asset) ->
                val destination = downloadsDirectory.resolve(asset.name)
                github.download(asset, destination)
                if (!Files.isRegularFile(destination)) {
                    fail("DOWNLOAD_MISSING", "Download did not create ${asset.name}")
                }
                val downloadedSize = Files.size(destination)
                if (downloadedSize != asset.size) {
                    fail(
                        "DOWNLOADED_SIZE_MISMATCH",
                        "${asset.name}: downloaded $downloadedSize bytes, GitHub reports ${asset.size}",
                    )
                }
                val sha256 = sha256(destination)
                validateGitHubDigest(asset, sha256)
                val inspection = apkInspector.inspect(destination)
                if (!inspection.signatureVerified) {
                    fail("APK_SIGNATURE_INVALID", "apksigner rejected ${asset.name}")
                }
                if (inspection.signingCertificateSha256.isEmpty()) {
                    fail("SIGNING_CERTIFICATE_MISSING", "No signer certificate digest for ${asset.name}")
                }
                if (inspection.packageName != artifact.packageName) {
                    fail(
                        "PACKAGE_NAME_MISMATCH",
                        "${asset.name}: ${inspection.packageName} != ${artifact.packageName}",
                    )
                }
                if (inspection.versionCode != artifact.artifactVersionCode) {
                    fail(
                        "VERSION_CODE_MISMATCH",
                        "${asset.name}: ${inspection.versionCode} != ${artifact.artifactVersionCode}",
                    )
                }
                results += ArtifactAuditResult(
                    releaseTag = releaseTag,
                    githubAssetId = asset.id,
                    artifactFilename = asset.name,
                    sourceUrl = asset.downloadUrl,
                    downloadedSize = downloadedSize,
                    githubAssetDigest = asset.digest,
                    sha256 = sha256,
                    packageName = inspection.packageName,
                    versionName = inspection.versionName,
                    versionCode = inspection.versionCode,
                    signingCertificateSha256 = inspection.signingCertificateSha256,
                    apkSignatureVerificationResult = "PASS",
                    auditedAt = auditedAt,
                )
            }
            CatalogAuditReport(releaseTag, AuditStatus.PASS, results, emptyList(), auditedAt)
        } catch (failure: AuditClosedException) {
            CatalogAuditReport(
                releaseTag = releaseTag,
                status = AuditStatus.FAIL,
                artifacts = results,
                failures = listOf(AuditFailure(failure.code, failure.message.orEmpty())),
                auditedAt = auditedAt,
            )
        } catch (failure: Exception) {
            CatalogAuditReport(
                releaseTag = releaseTag,
                status = AuditStatus.FAIL,
                artifacts = results,
                failures = listOf(
                    AuditFailure(
                        code = "AUDIT_EXECUTION_ERROR",
                        message = failure.message ?: failure.javaClass.simpleName,
                    ),
                ),
                auditedAt = auditedAt,
            )
        }
    }

    private fun requireOfficialExplicitMetadata(artifact: ComponentArtifact) {
        if (artifact.sourceType != ComponentSourceType.OFFICIAL_MICROG_GITHUB ||
            artifact.metadataSource != ComponentSourceType.OFFICIAL_MICROG_GITHUB
        ) {
            fail("SOURCE_NOT_ALLOWED", "Only OFFICIAL_MICROG_GITHUB is allowed")
        }
        if (artifact.artifactFilename.isNullOrBlank() ||
            artifact.artifactVersionCode.isNullOrBlank() ||
            artifact.sourceReleaseUrl.isNullOrBlank()
        ) {
            fail("CATALOG_METADATA_MISSING", "Explicit artifact metadata is required")
        }
        val filename = artifact.artifactFilename
        if (Path.of(filename).fileName.toString() != filename) {
            fail("CATALOG_FILENAME_INVALID", "Artifact filename must not contain a path")
        }
    }

    private fun validateAssetMetadata(releaseTag: String, asset: GitHubAssetMetadata) {
        val expectedUrl =
            "https://github.com/microg/GmsCore/releases/download/$releaseTag/${asset.name}"
        if (asset.state != "uploaded" ||
            asset.contentType != "application/vnd.android.package-archive" ||
            asset.size <= 0L ||
            asset.downloadUrl != expectedUrl
        ) {
            fail("ASSET_METADATA_MISMATCH", "Official asset metadata mismatch for ${asset.name}")
        }
    }

    private fun validateGitHubDigest(asset: GitHubAssetMetadata, localSha256: String) {
        val digest = asset.digest ?: return
        val parts = digest.split(":", limit = 2)
        if (parts.size != 2 || parts[0] != "sha256") {
            fail("ASSET_DIGEST_FORMAT_UNSUPPORTED", "Unsupported GitHub digest: $digest")
        }
        if (!parts[1].equals(localSha256, ignoreCase = true)) {
            fail("SHA256_MISMATCH", "${asset.name}: local SHA-256 differs from GitHub digest")
        }
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

    private fun fail(code: String, message: String): Nothing =
        throw AuditClosedException(code, message)
}

private class AuditClosedException(
    val code: String,
    message: String,
) : RuntimeException(message)
