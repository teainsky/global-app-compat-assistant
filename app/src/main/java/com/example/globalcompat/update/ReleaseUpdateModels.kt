package com.example.globalcompat.update

import com.google.gson.JsonParser
import java.net.URI
import java.util.Locale

enum class ReleaseUpdateStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    UNAVAILABLE,
    DOWNLOADING,
    VERIFYING,
    WAITING_FOR_INSTALL_PERMISSION,
    READY_TO_INSTALL,
    FAILED,
}

data class ReleaseApkAsset(
    val assetId: Long,
    val filename: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class AvailableRelease(
    val tagName: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val asset: ReleaseApkAsset,
)

data class ReleaseUpdateUiState(
    val status: ReleaseUpdateStatus = ReleaseUpdateStatus.IDLE,
    val release: AvailableRelease? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val message: String? = null,
)

data class VerifiedReleaseApk(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val signerSha256: String,
)

interface UpdateCheckStateStore {
    fun lastAttemptAt(): Long?
    fun recordAttempt(atMillis: Long)
    fun cachedRelease(): AvailableRelease?
    fun saveRelease(release: AvailableRelease?)
}

fun interface LatestReleaseSource {
    fun fetchLatest(): AvailableRelease
}

object ReleaseApkVerificationPolicy {
    fun requireValid(
        release: AvailableRelease,
        expectedPackageName: String,
        currentVersionCode: Long,
        expectedSignerSha256: String,
        actual: VerifiedReleaseApk,
    ) {
        check(actual.sha256.equals(release.asset.sha256, ignoreCase = true)) {
            "Downloaded APK SHA-256 did not match GitHub metadata"
        }
        check(actual.packageName == expectedPackageName) {
            "Downloaded APK package did not match this app"
        }
        check(actual.versionCode > currentVersionCode) {
            "Downloaded APK was not newer than the installed app"
        }
        check(actual.versionName == release.versionName) {
            "Downloaded APK version did not match the GitHub release tag"
        }
        check(actual.signerSha256.equals(expectedSignerSha256, ignoreCase = true)) {
            "Downloaded APK was not signed by the pinned formal release certificate"
        }
    }
}

class DailyReleaseUpdateChecker(
    private val store: UpdateCheckStateStore,
    private val source: LatestReleaseSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun checkIfDue(currentVersionName: String): ReleaseUpdateUiState {
        val cached = store.cachedRelease()?.takeIf {
            ReleaseVersionComparator.isNewer(it.versionName, currentVersionName)
        }
        val currentTime = now()
        val lastAttempt = store.lastAttemptAt()
        if (lastAttempt != null && currentTime - lastAttempt < CHECK_INTERVAL_MILLIS) {
            return cached?.let {
                ReleaseUpdateUiState(ReleaseUpdateStatus.UPDATE_AVAILABLE, it)
            } ?: ReleaseUpdateUiState(ReleaseUpdateStatus.UP_TO_DATE)
        }

        // Record before network I/O so failures cannot create a tight retry loop on every launch.
        store.recordAttempt(currentTime)
        return runCatching { source.fetchLatest() }.fold(
            onSuccess = { release ->
                if (ReleaseVersionComparator.isNewer(release.versionName, currentVersionName)) {
                    store.saveRelease(release)
                    ReleaseUpdateUiState(ReleaseUpdateStatus.UPDATE_AVAILABLE, release)
                } else {
                    store.saveRelease(null)
                    ReleaseUpdateUiState(ReleaseUpdateStatus.UP_TO_DATE)
                }
            },
            onFailure = {
                cached?.let { release ->
                    ReleaseUpdateUiState(ReleaseUpdateStatus.UPDATE_AVAILABLE, release)
                } ?: ReleaseUpdateUiState(
                    status = ReleaseUpdateStatus.UNAVAILABLE,
                    message = "暂时无法检查更新，不影响设备检测。",
                )
            },
        )
    }

    companion object {
        const val CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

object GitHubLatestReleaseParser {
    fun parse(json: String): AvailableRelease {
        val root = JsonParser.parseString(json).asJsonObject
        check(!root.requiredBoolean("draft")) { "Draft releases are not eligible" }
        check(!root.requiredBoolean("prerelease")) { "Prereleases are not eligible" }

        val tagName = root.requiredString("tag_name")
        val versionName = tagName.removePrefix("v")
        check(ReleaseVersionComparator.isValid(versionName)) { "Release tag is not a version" }
        val apkAssets = root.getAsJsonArray("assets")
            ?.map { it.asJsonObject }
            ?.filter { asset ->
                asset.requiredString("name").lowercase(Locale.ROOT).endsWith(".apk")
            }
            .orEmpty()
        check(apkAssets.size == 1) { "Latest release must contain exactly one APK asset" }
        val asset = apkAssets.single()
        val digest = asset.requiredString("digest")
        check(digest.startsWith("sha256:", ignoreCase = true)) {
            "GitHub asset SHA-256 digest is required"
        }
        val sha256 = digest.substringAfter(':').lowercase(Locale.ROOT)
        check(SHA_256.matches(sha256)) { "GitHub asset SHA-256 digest is invalid" }
        val downloadUrl = asset.requiredString("browser_download_url")
        val uri = URI.create(downloadUrl)
        check(uri.scheme.equals("https", ignoreCase = true)) { "APK URL must use HTTPS" }
        check(uri.host.equals("github.com", ignoreCase = true)) {
            "APK must be served from the official GitHub release URL"
        }

        return AvailableRelease(
            tagName = tagName,
            versionName = versionName,
            title = root.optionalString("name").ifBlank { tagName },
            notes = root.optionalString("body").trim().take(MAX_NOTES_LENGTH),
            publishedAt = root.requiredString("published_at"),
            asset = ReleaseApkAsset(
                assetId = asset.get("id")?.asLong ?: error("GitHub asset id is missing"),
                filename = asset.requiredString("name"),
                downloadUrl = downloadUrl,
                sizeBytes = asset.get("size")?.asLong?.takeIf { it > 0L }
                    ?: error("GitHub asset size is invalid"),
                sha256 = sha256,
            ),
        )
    }

    private fun com.google.gson.JsonObject.requiredString(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("GitHub release field $name is missing")

    private fun com.google.gson.JsonObject.optionalString(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun com.google.gson.JsonObject.requiredBoolean(name: String): Boolean =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean
            ?: error("GitHub release field $name is missing")

    private val SHA_256 = Regex("^[0-9a-f]{64}$")
    private const val MAX_NOTES_LENGTH = 2_000
}

object ReleaseVersionComparator {
    fun isValid(version: String): Boolean = VERSION.matches(version)

    fun isNewer(candidate: String, current: String): Boolean {
        val left = parse(candidate) ?: return false
        val right = parse(current) ?: return false
        for (index in 0..2) {
            val comparison = left.numbers[index].compareTo(right.numbers[index])
            if (comparison != 0) return comparison > 0
        }
        if (left.qualifier == right.qualifier) return false
        if (left.qualifier == null) return true
        if (right.qualifier == null) return false
        return compareQualifier(left.qualifier, right.qualifier) > 0
    }

    private fun parse(value: String): ParsedVersion? {
        val match = VERSION.matchEntire(value.removePrefix("v")) ?: return null
        return ParsedVersion(
            numbers = listOf(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            ),
            qualifier = match.groupValues[4].takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT),
        )
    }

    private fun compareQualifier(left: String, right: String): Int {
        val leftMatch = QUALIFIER.matchEntire(left)
        val rightMatch = QUALIFIER.matchEntire(right)
        if (leftMatch != null && rightMatch != null && leftMatch.groupValues[1] == rightMatch.groupValues[1]) {
            return leftMatch.groupValues[2].toInt().compareTo(rightMatch.groupValues[2].toInt())
        }
        return left.compareTo(right)
    }

    private data class ParsedVersion(val numbers: List<Int>, val qualifier: String?)

    private val VERSION = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$")
    private val QUALIFIER = Regex("^([a-z.-]+?)(\\d+)$")
}
