package com.example.globalcompat.audit

import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val releaseTag = argument(args, "--release-tag")
    val outputDirectory = Path.of("build", "catalog-audit", releaseTag)
    val sdkValue = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    val report = CatalogAuditEngine(
        github = OfficialGitHubReleaseClient(),
        apkInspector = AndroidSdkApkInspector(Path.of(sdkValue)),
    ).audit(releaseTag, outputDirectory)
    AuditReportWriter().write(report, outputDirectory)
    println("Catalog audit ${report.status}: ${outputDirectory.resolve("audit-report.json")}")
    if (report.status != AuditStatus.PASS) exitProcess(2)
}

private fun argument(args: Array<String>, name: String): String {
    val index = args.indexOf(name)
    require(index >= 0 && index + 1 < args.size) { "$name is required" }
    return args[index + 1]
}
