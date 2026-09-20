package com.example.globalcompat.audit

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile

class AdbHostDeviceBridge(
    androidSdk: Path,
    private val serial: String?,
) : HostDeviceBridge {
    private val adb = executable(androidSdk.resolve("platform-tools"), "adb")

    override fun installedApkPaths(packageName: String): List<String> {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid package name" }
        val result = run("shell", "pm", "path", packageName)
        return result.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter(String::isNotBlank)
            .toList()
    }

    override fun pull(remotePath: String, destination: Path) {
        require(remotePath.startsWith("/") && remotePath.endsWith(".apk")) {
            "Unexpected installed APK path"
        }
        Files.createDirectories(destination.parent)
        run("pull", remotePath, destination.toString())
    }

    private fun run(vararg arguments: String): String {
        val command = buildList {
            add(adb.toString())
            serial?.takeIf(String::isNotBlank)?.let {
                add("-s")
                add(it)
            }
            addAll(arguments)
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val outputFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(PROCESS_SHUTDOWN_SECONDS, TimeUnit.SECONDS)
            error("adb timed out after $COMMAND_TIMEOUT_SECONDS seconds")
        }
        val output = outputFuture.get(PROCESS_SHUTDOWN_SECONDS, TimeUnit.SECONDS)
        val exitCode = process.exitValue()
        check(exitCode == 0) { "adb failed ($exitCode): $output" }
        return output
    }

    private fun executable(directory: Path, baseName: String): Path {
        val windows = directory.resolve("$baseName.exe")
        val resolved = if (windows.isRegularFile()) windows else directory.resolve(baseName)
        require(resolved.isRegularFile()) { "adb not found in Android SDK" }
        return resolved
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_.]+")
        const val COMMAND_TIMEOUT_SECONDS = 60L
        const val PROCESS_SHUTDOWN_SECONDS = 5L
    }
}
