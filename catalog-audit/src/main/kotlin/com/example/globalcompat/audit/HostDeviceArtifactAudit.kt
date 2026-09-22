package com.example.globalcompat.audit

import com.example.globalcompat.catalog.ArtifactIntegrityStatus
import com.example.globalcompat.catalog.ComponentArtifact
import com.example.globalcompat.catalog.ComponentCatalog
import com.example.globalcompat.catalog.InstalledArtifactSignatureStatus
import com.example.globalcompat.catalog.RuntimeTrustedCatalogRepository
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

data class HostArtifactEvidence(
    val packageName: String,
    val versionCode: String,
    val versionName: String,
    val locallyCalculatedSha256: String,
    val signingCertificateSha256: List<String>,
    val apkSignatureVerified: Boolean,
)

data class HostDeviceArtifactAuditResult(
    val componentId: String,
    val packageName: String,
    val expectedAssetId: Long?,
    val expectedArtifactFilename: String?,
    val installedApkPath: String?,
    val versionCode: String?,
    val versionName: String?,
    val locallyCalculatedSha256: String?,
    val signingCertificateSha256: List<String>,
    val apksignerVerificationResult: String,
    val signatureStatus: InstalledArtifactSignatureStatus,
    val failure: String?,
)

data class HostDeviceArtifactAuditReport(
    val schemaVersion: Int,
    val status: AuditStatus,
    val auditedAt: String,
    val artifacts: List<HostDeviceArtifactAuditResult>,
)

class HostArtifactEvidenceEvaluator {
    fun evaluate(
        artifact: ComponentArtifact,
        evidence: HostArtifactEvidence,
    ): InstalledArtifactSignatureStatus {
        val expectedVersionCode = artifact.artifactVersionCode
        val expectedVersionName = artifact.artifactVersionName
        val expectedSha256 = artifact.sha256?.normalizeDigest()
        val expectedSigner = artifact.signingCertificateDigest?.normalizeDigest()
        val actualSigners = evidence.signingCertificateSha256
            .map { it.normalizeDigest() }
            .toSet()
        if (artifact.integrityStatus != ArtifactIntegrityStatus.SIGNATURE_VERIFIED ||
            expectedVersionCode.isNullOrBlank() ||
            expectedVersionName.isNullOrBlank() ||
            expectedSha256.isNullOrBlank() ||
            expectedSigner.isNullOrBlank() ||
            evidence.packageName != artifact.packageName ||
            evidence.versionCode != expectedVersionCode ||
            evidence.versionName != expectedVersionName
        ) {
            return InstalledArtifactSignatureStatus.UNKNOWN
        }
        if (!evidence.apkSignatureVerified) {
            return InstalledArtifactSignatureStatus.SIGNER_MISMATCH
        }
        if (evidence.locallyCalculatedSha256.normalizeDigest() == expectedSha256 &&
            actualSigners == setOf(expectedSigner)
        ) {
            return InstalledArtifactSignatureStatus.ACTUAL_ARTIFACT_MATCH
        }
        return if (actualSigners.isNotEmpty() && actualSigners != setOf(expectedSigner)) {
            InstalledArtifactSignatureStatus.SIGNER_MISMATCH
        } else {
            InstalledArtifactSignatureStatus.UNKNOWN
        }
    }

    private fun String.normalizeDigest(): String = replace(":", "").lowercase()
}

interface HostDeviceBridge {
    fun installedApkPaths(packageName: String): List<String>
    fun pull(remotePath: String, destination: Path)
}

class HostDeviceArtifactAuditor(
    private val bridge: HostDeviceBridge,
    private val apkInspector: ApkInspector,
    private val evaluator: HostArtifactEvidenceEvaluator = HostArtifactEvidenceEvaluator(),
    private val createTemporaryDirectory: () -> Path = {
        Files.createTempDirectory("global-compat-device-audit-")
    },
    private val catalog: ComponentCatalog? =
        RuntimeTrustedCatalogRepository.instance.currentSnapshot()?.catalog,
) {
    fun audit(): HostDeviceArtifactAuditReport {
        val temporaryDirectory = createTemporaryDirectory()
        val artifacts = catalog?.releases.orEmpty()
            .flatMap { it.artifacts }
            .filter { it.packageName in TARGET_PACKAGES }
        return try {
            val results = artifacts.map { artifact -> auditOne(artifact, temporaryDirectory) }
            HostDeviceArtifactAuditReport(
                schemaVersion = 1,
                status = if (results.all {
                        it.signatureStatus ==
                            InstalledArtifactSignatureStatus.ACTUAL_ARTIFACT_MATCH
                    }
                ) {
                    AuditStatus.PASS
                } else {
                    AuditStatus.FAIL
                },
                auditedAt = Instant.now().toString(),
                artifacts = results,
            )
        } finally {
            deleteTemporaryDirectory(temporaryDirectory)
        }
    }

    private fun auditOne(
        artifact: ComponentArtifact,
        temporaryDirectory: Path,
    ): HostDeviceArtifactAuditResult {
        var installedPath: String? = null
        return try {
            val paths = bridge.installedApkPaths(artifact.packageName)
            require(paths.size == 1) {
                "Expected one installed APK path for ${artifact.packageName}, found ${paths.size}"
            }
            installedPath = paths.single()
            val localApk = temporaryDirectory.resolve(
                artifact.artifactFilename ?: "${artifact.packageName}.apk",
            )
            bridge.pull(installedPath, localApk)
            require(localApk.isRegularFile() && localApk.fileSize() > 0L) {
                "ADB pull did not produce a complete APK for ${artifact.packageName}"
            }
            val localSha256 = sha256(localApk)
            val inspection = apkInspector.inspect(localApk)
            val evidence = HostArtifactEvidence(
                packageName = inspection.packageName,
                versionCode = inspection.versionCode,
                versionName = inspection.versionName,
                locallyCalculatedSha256 = localSha256,
                signingCertificateSha256 = inspection.signingCertificateSha256,
                apkSignatureVerified = inspection.signatureVerified,
            )
            HostDeviceArtifactAuditResult(
                componentId = artifact.componentId,
                packageName = artifact.packageName,
                expectedAssetId = artifact.githubAssetId,
                expectedArtifactFilename = artifact.artifactFilename,
                installedApkPath = installedPath,
                versionCode = inspection.versionCode,
                versionName = inspection.versionName,
                locallyCalculatedSha256 = localSha256,
                signingCertificateSha256 = inspection.signingCertificateSha256,
                apksignerVerificationResult = if (inspection.signatureVerified) "PASS" else "FAIL",
                signatureStatus = evaluator.evaluate(artifact, evidence),
                failure = null,
            )
        } catch (error: Exception) {
            HostDeviceArtifactAuditResult(
                componentId = artifact.componentId,
                packageName = artifact.packageName,
                expectedAssetId = artifact.githubAssetId,
                expectedArtifactFilename = artifact.artifactFilename,
                installedApkPath = installedPath,
                versionCode = null,
                versionName = null,
                locallyCalculatedSha256 = null,
                signingCertificateSha256 = emptyList(),
                apksignerVerificationResult = "FAIL",
                signatureStatus = InstalledArtifactSignatureStatus.UNKNOWN,
                failure = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun deleteTemporaryDirectory(directory: Path) {
        if (!directory.exists()) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private companion object {
        val TARGET_PACKAGES = setOf("com.google.android.gms", "com.android.vending")
    }
}

class HostDeviceAuditReportWriter {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun write(report: HostDeviceArtifactAuditReport, outputDirectory: Path) {
        Files.createDirectories(outputDirectory)
        Files.writeString(
            outputDirectory.resolve("host-audit-report.json"),
            gson.toJson(report) + "\n",
        )
        Files.writeString(
            outputDirectory.resolve("host-audit-report.txt"),
            buildString {
                appendLine("Host device artifact audit: ${report.status}")
                report.artifacts.forEach { artifact ->
                    appendLine(
                        "${artifact.packageName}: ${artifact.signatureStatus}; " +
                            "sha256=${artifact.locallyCalculatedSha256 ?: "unavailable"}",
                    )
                    artifact.failure?.let { appendLine("  failure: $it") }
                }
            },
        )
    }
}
