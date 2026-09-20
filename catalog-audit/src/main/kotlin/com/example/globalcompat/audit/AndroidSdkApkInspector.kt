package com.example.globalcompat.audit

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

class AndroidSdkApkInspector(
    private val androidSdk: Path,
) : ApkInspector {
    private val apkAnalyzer = executable(
        androidSdk.resolve("cmdline-tools/latest/bin"),
        "apkanalyzer",
    )
    private val apkSigner = Files.list(androidSdk.resolve("build-tools")).use { versions ->
        versions.filter(Files::isDirectory)
            .sorted(Comparator.reverseOrder())
            .map { executable(it, "apksigner") }
            .filter(Path::isRegularFile)
            .findFirst()
            .orElseThrow { IllegalStateException("apksigner not found in Android SDK") }
    }

    override fun inspect(apk: Path): ApkInspection {
        val packageName = run(apkAnalyzer, "manifest", "application-id", apk.toString()).output.trim()
        val versionCode = run(apkAnalyzer, "manifest", "version-code", apk.toString()).output.trim()
        val versionName = run(apkAnalyzer, "manifest", "version-name", apk.toString()).output.trim()
        val signature = run(
            apkSigner,
            "verify",
            "--verbose",
            "--print-certs",
            apk.toString(),
            requireSuccess = false,
        )
        val certificateDigests = CERTIFICATE_SHA256.findAll(signature.output)
            .map { it.groupValues[1].replace(":", "").lowercase() }
            .distinct()
            .toList()
        return ApkInspection(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            signingCertificateSha256 = certificateDigests,
            signatureVerified = signature.exitCode == 0,
            signatureVerificationOutput = signature.output,
        )
    }

    private fun run(
        executable: Path,
        vararg arguments: String,
        requireSuccess: Boolean = true,
    ): ProcessResult {
        val process = ProcessBuilder(listOf(executable.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (requireSuccess && exitCode != 0) {
            error("${executable.fileName} failed ($exitCode): $output")
        }
        return ProcessResult(exitCode, output)
    }

    private fun executable(directory: Path, baseName: String): Path {
        val windows = directory.resolve("$baseName.bat")
        return if (windows.isRegularFile()) windows else directory.resolve(baseName)
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    private companion object {
        val CERTIFICATE_SHA256 =
            Regex("Signer #\\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)")
    }
}
