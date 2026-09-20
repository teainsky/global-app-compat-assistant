package com.example.globalcompat.audit

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

class AuditReportWriter {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun write(report: CatalogAuditReport, outputDirectory: Path) {
        Files.createDirectories(outputDirectory)
        Files.writeString(outputDirectory.resolve("audit-report.json"), gson.toJson(report) + "\n")
        if (report.status == AuditStatus.PASS) {
            val manifest = AuditedManifest(
                schemaVersion = 1,
                releaseTag = report.releaseTag,
                auditStatus = report.status,
                generatedAt = report.auditedAt,
                artifacts = report.artifacts,
            )
            Files.writeString(
                outputDirectory.resolve("audited-manifest.json"),
                gson.toJson(manifest) + "\n",
            )
        } else {
            Files.deleteIfExists(outputDirectory.resolve("audited-manifest.json"))
        }
        val text = buildString {
            appendLine("Catalog audit: ${report.releaseTag}")
            appendLine("Result: ${report.status}")
            report.sourceRecords.forEach { record ->
                appendLine(
                    "Source: ${record.componentId} / ${record.sourceType} = " +
                        record.availabilityStatus,
                )
            }
            report.warnings.forEach { warning ->
                appendLine("Warning [${warning.code}]: ${warning.message}")
            }
            report.artifacts.forEach { artifact ->
                appendLine("- ${artifact.filename}")
                appendLine("  source: ${artifact.sourceType}")
                appendLine("  asset ID: ${artifact.assetId}")
                appendLine("  asset size: ${artifact.assetSize}")
                appendLine("  GitHub digest: ${artifact.githubDigest}")
                appendLine("  local SHA-256: ${artifact.locallyCalculatedSha256}")
                appendLine("  package: ${artifact.packageName}")
                appendLine("  version: ${artifact.versionName} (${artifact.versionCode})")
                appendLine("  signer SHA-256: ${artifact.signingCertificateSha256.joinToString()}")
                appendLine("  compatibility: ${artifact.compatibilityValidationStatus}")
            }
            report.failures.forEach { failure ->
                appendLine("Failure [${failure.code}]: ${failure.message}")
            }
        }
        Files.writeString(outputDirectory.resolve("audit-report.txt"), text)
    }
}
