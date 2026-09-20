package com.example.globalcompat.audit

import com.example.globalcompat.catalog.ArtifactDescriptor
import com.example.globalcompat.catalog.ArtifactSourceRecord
import com.example.globalcompat.catalog.ArtifactIntegrityStatus
import com.example.globalcompat.catalog.CompatibilityValidationStatus
import java.nio.file.Path

enum class AuditStatus {
    PASS,
    FAIL,
}

data class GitHubReleaseMetadata(
    val releaseTag: String,
    val releaseUrl: String,
    val releaseNotes: String,
    val assets: List<GitHubAssetMetadata>,
    val publishedAt: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

data class GitHubAssetMetadata(
    val id: Long,
    val name: String,
    val contentType: String,
    val state: String,
    val size: Long,
    val digest: String?,
    val downloadUrl: String,
    val apiUrl: String? = null,
)

data class ApkInspection(
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    val signingCertificateSha256: List<String>,
    val signatureVerified: Boolean,
    val signatureVerificationOutput: String,
)

data class ArtifactAuditResult(
    val componentId: String,
    val releaseTag: String,
    val sourceType: String,
    val filename: String,
    val assetId: Long,
    val assetSize: Long,
    val sourceUrl: String,
    val githubDigest: String?,
    val githubDigestMatches: Boolean?,
    val locallyCalculatedSha256: String,
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    val signingCertificateSha256: List<String>,
    val apksignerVerificationResult: String,
    val artifactIntegrityStatus: ArtifactIntegrityStatus,
    val compatibilityValidationStatus: CompatibilityValidationStatus,
    val auditedAt: String,
)

data class AuditWarning(
    val code: String,
    val componentId: String,
    val message: String,
)

data class AuditFailure(
    val code: String,
    val message: String,
)

data class CatalogAuditReport(
    val releaseTag: String,
    val status: AuditStatus,
    val sourceRecords: List<ArtifactSourceRecord>,
    val artifacts: List<ArtifactAuditResult>,
    val warnings: List<AuditWarning>,
    val failures: List<AuditFailure>,
    val auditedAt: String,
)

data class AuditedManifest(
    val schemaVersion: Int,
    val releaseTag: String,
    val auditStatus: AuditStatus,
    val generatedAt: String,
    val artifacts: List<ArtifactAuditResult>,
)

interface GitHubReleaseClient {
    fun getRelease(releaseTag: String): GitHubReleaseMetadata

    fun listReleases(): List<GitHubReleaseMetadata> = error("Release inventory is not supported")
}

interface ArtifactSourceResolver {
    fun resolve(releaseTag: String, descriptors: List<ArtifactDescriptor>): List<ArtifactSourceRecord>
}

data class OfficialPageSnapshot(
    val finalUrl: String?,
    val body: String?,
    val error: String?,
)

interface OfficialPageClient {
    fun fetch(url: String): OfficialPageSnapshot
}

interface OfficialArtifactDownloader {
    fun download(record: ArtifactSourceRecord, destination: Path)
}

interface ApkInspector {
    fun inspect(apk: Path): ApkInspection
}
