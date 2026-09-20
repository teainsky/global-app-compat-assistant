package com.example.globalcompat.audit

import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if ("--inventory" in args) {
        runInventory()
        return
    }
    val releaseTag = argument(args, "--release-tag")
    val outputDirectory = Path.of("build", "catalog-audit", releaseTag)
    val sdkValue = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    val github = OfficialGitHubReleaseClient()
    val report = CatalogAuditEngine(
        sourceResolver = OfficialArtifactSourceResolver(github),
        downloader = HttpOfficialArtifactDownloader(),
        apkInspector = AndroidSdkApkInspector(Path.of(sdkValue)),
    ).audit(releaseTag, outputDirectory)
    AuditReportWriter().write(report, outputDirectory)
    println("Catalog audit ${report.status}: ${outputDirectory.resolve("audit-report.json")}")
    if (report.status != AuditStatus.PASS) exitProcess(2)
}

private fun runInventory() {
    val outputDirectory = Path.of("build", "catalog-audit", "release-inventory")
    val report = ReleaseInventoryScanner(OfficialGitHubReleaseClient()).scan(limit = 10)
    ReleaseInventoryReportWriter().write(report, outputDirectory)
    println("Release inventory: ${outputDirectory.resolve("inventory-report.json")}")
    if (report.latestCompleteHuaweiPair == null) exitProcess(2)
}

private fun argument(args: Array<String>, name: String): String {
    val index = args.indexOf(name)
    require(index >= 0 && index + 1 < args.size) { "$name is required" }
    return args[index + 1]
}
