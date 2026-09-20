package com.example.globalcompat.audit

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

class ReleaseInventoryReportWriter {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun write(report: ReleaseInventoryReport, outputDirectory: Path) {
        Files.createDirectories(outputDirectory)
        Files.writeString(
            outputDirectory.resolve("inventory-report.json"),
            gson.toJson(report) + "\n",
        )
        val text = buildString {
            appendLine("microG stable release inventory")
            appendLine("Scanned at: ${report.scannedAt}")
            report.releases.forEach { release ->
                appendLine(
                    "${release.releaseTag}\t${release.publishedAt}\t" +
                        "completeHuaweiPair=${release.completeHuaweiPair}\t" +
                        "gms=${release.huaweiGmsCoreAsset?.filename ?: "MISSING"}\t" +
                        "companion=${release.huaweiCompanionAsset?.filename ?: "MISSING"}",
                )
            }
            val candidate = report.latestCompleteHuaweiPair
            if (candidate == null) {
                appendLine("Candidate: NONE (${report.failureCode})")
            } else {
                appendLine("Candidate: ${candidate.releaseTag} (${candidate.status})")
            }
        }
        Files.writeString(outputDirectory.resolve("inventory-report.txt"), text)
    }
}
