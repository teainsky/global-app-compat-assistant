package com.example.globalcompat.audit

import com.example.globalcompat.catalog.ArtifactDescriptor
import com.example.globalcompat.catalog.ArtifactSourceRecord
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
)

data class GitHubAssetMetadata(
    val id: Long,
    val name: String,
    val contentType: String,
    val state: String,
    val size: Long,
    val digest: String?,
    val downloadUrl: String,
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
    val releaseTag: String,
    val sourceType: String,
    val sourceAssetId: String?,
    val artifactFilename: String,
    val sourceUrl: String,
    val downloadedSize: Long,
    val githubAssetDigest: String?,
    val sha256: String,
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    val signingCertificateSha256: List<String>,
    val apkSignatureVerificationResult: String,
    val auditedAt: String,
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
    val failures: List<AuditFailure>,
    val auditedAt: String,
)

interface GitHubReleaseClient {
    fun getRelease(releaseTag: String): GitHubReleaseMetadata

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
