package com.example.globalcompat.audit

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

class AuditReportWriter {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun write(report: CatalogAuditReport, outputDirectory: Path) {
        Files.createDirectories(outputDirectory)
        Files.writeString(outputDirectory.resolve("audit-report.json"), gson.toJson(report) + "\n")
        val text = buildString {
            appendLine("Catalog audit: ${report.releaseTag}")
            appendLine("Result: ${report.status}")
            report.sourceRecords.forEach { record ->
                appendLine(
                    "Source: ${record.componentId} / ${record.sourceType} = " +
                        record.availabilityStatus,
                )
            }
            report.artifacts.forEach { artifact ->
                appendLine("- ${artifact.artifactFilename}")
                appendLine("  source: ${artifact.sourceType}")
                appendLine("  asset ID: ${artifact.sourceAssetId}")
                appendLine("  SHA-256: ${artifact.sha256}")
                appendLine("  package: ${artifact.packageName}")
                appendLine("  version: ${artifact.versionName} (${artifact.versionCode})")
                appendLine("  signer SHA-256: ${artifact.signingCertificateSha256.joinToString()}")
            }
            report.failures.forEach { failure ->
                appendLine("Failure [${failure.code}]: ${failure.message}")
            }
        }
        Files.writeString(outputDirectory.resolve("audit-report.txt"), text)
    }
}
