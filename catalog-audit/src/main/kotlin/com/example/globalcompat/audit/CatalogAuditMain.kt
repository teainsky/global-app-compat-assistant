package com.example.globalcompat.audit

import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if ("--generate-catalog-signing-key" in args) {
        runCatalogSigningKeyGeneration(args)
        return
    }
    if ("--publish-signed-catalog" in args) {
        runSignedCatalogPublication(args)
        return
    }
    if ("--inventory" in args) {
        runInventory()
        return
    }
    if ("--device-audit" in args) {
        runDeviceAudit(args)
        return
    }
    if ("--validation-evidence" in args) {
        runValidationEvidence(args)
        return
    }
    if ("--publish-device-record" in args) {
        runDeviceRecordPublication(args)
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
        downloader = CurlOfficialArtifactDownloader(),
        apkInspector = AndroidSdkApkInspector(Path.of(sdkValue)),
    ).audit(releaseTag, outputDirectory)
    AuditReportWriter().write(report, outputDirectory)
    println("Catalog audit ${report.status}: ${outputDirectory.resolve("audit-report.json")}")
    if (report.status != AuditStatus.PASS) exitProcess(2)
}

private fun runCatalogSigningKeyGeneration(args: Array<String>) {
    val privateKeyPath = Path.of(argument(args, "--private-key-pkcs8"))
    val publicKeyPath = Path.of(argument(args, "--public-key-x509"))
    CatalogSigningKeyGenerator.generate(privateKeyPath, publicKeyPath)
    println("Generated catalog signing keypair; private key remains local: $privateKeyPath")
}

private fun runSignedCatalogPublication(args: Array<String>) {
    val evidencePath = Path.of(argument(args, "--evidence"))
    val inputCatalogPath = Path.of(argument(args, "--input-catalog"))
    val privateKeyPath = Path.of(argument(args, "--private-key-pkcs8"))
    val publicKeyPath = Path.of(argument(args, "--public-key-x509"))
    val outputCatalogPath = Path.of(argument(args, "--output-catalog"))
    val outputSignaturePath = Path.of(argument(args, "--output-signature"))
    val publication = SignedCatalogPublishingPipeline().publish(
        evidenceBytes = java.nio.file.Files.readAllBytes(evidencePath),
        expectedEvidenceSha256 = argument(args, "--expected-evidence-sha256"),
        existingCatalogBytes = java.nio.file.Files.readAllBytes(inputCatalogPath),
        privateKeyPkcs8 = java.nio.file.Files.readAllBytes(privateKeyPath),
        publicKeyX509 = java.nio.file.Files.readAllBytes(publicKeyPath),
        publishedAt = argument(args, "--published-at"),
    )
    SignedCatalogWriter.write(publication, outputCatalogPath, outputSignaturePath)
    println("Signed compatibility catalog published: $outputCatalogPath")
}

private fun runDeviceRecordPublication(args: Array<String>) {
    val evidencePath = Path.of(argument(args, "--evidence"))
    val expectedDigest = argument(args, "--expected-evidence-sha256")
    val outputPath = Path.of(
        optionalArgument(args, "--output") ?: "build/catalog-audit/verified-device-record.json",
    )
    when (
        val result = DeviceVerificationPublishingPipeline().publish(
            evidenceBytes = java.nio.file.Files.readAllBytes(evidencePath),
            expectedEvidenceSha256 = expectedDigest,
        )
    ) {
        is com.example.globalcompat.validation.DeviceRecordPublicationResult.Approved -> {
            VerifiedDeviceRecordWriter.write(result.record, outputPath)
            println("Verified device record awaiting manual catalog approval: $outputPath")
        }
        is com.example.globalcompat.validation.DeviceRecordPublicationResult.Rejected -> {
            error("Device record publication rejected: ${result.reasons.joinToString()}")
        }
    }
}

private fun runValidationEvidence(args: Array<String>) {
    val baselinePath = Path.of(argument(args, "--baseline"))
    val artifactAuditPath = optionalArgument(args, "--artifact-audit")?.let(Path::of)
    val outputPath = Path.of(
        optionalArgument(args, "--output")
            ?: "build/catalog-audit/device-validation-evidence.json",
    )
    val report = DeviceValidationEvidenceTool().evaluate(
        baselinePath = baselinePath,
        artifactAuditPath = artifactAuditPath,
        expectedBaselineSha256 = optionalArgument(args, "--expected-baseline-sha256"),
    )
    DeviceValidationEvidenceWriter.write(report, outputPath)
    println("Device validation evidence ${report.attainedLevel}: $outputPath")
}

private fun runDeviceAudit(args: Array<String>) {
    val sdkValue = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    val sdkPath = Path.of(sdkValue)
    val report = HostDeviceArtifactAuditor(
        bridge = AdbHostDeviceBridge(sdkPath, optionalArgument(args, "--serial")),
        apkInspector = AndroidSdkApkInspector(sdkPath),
    ).audit()
    val outputDirectory = Path.of("build", "catalog-audit", "device-artifact-audit")
    HostDeviceAuditReportWriter().write(report, outputDirectory)
    println("Host device audit ${report.status}: ${outputDirectory.resolve("host-audit-report.json")}")
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

private fun optionalArgument(args: Array<String>, name: String): String? {
    val index = args.indexOf(name)
    if (index < 0) return null
    require(index + 1 < args.size) { "$name requires a value" }
    return args[index + 1]
}
