package com.example.globalcompat.audit

import com.example.globalcompat.catalog.ArtifactDescriptor
import com.example.globalcompat.catalog.ArtifactSourceRecord
import com.example.globalcompat.catalog.ComponentSourceType
import com.example.globalcompat.catalog.SourceAvailabilityStatus
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.time.Duration

class OfficialArtifactSourceResolver(
    private val github: GitHubReleaseClient,
    private val pageClient: OfficialPageClient = HttpOfficialPageClient(),
) : ArtifactSourceResolver {
    override fun resolve(
        releaseTag: String,
        descriptors: List<ArtifactDescriptor>,
    ): List<ArtifactSourceRecord> {
        val githubResult = runCatching { github.getRelease(releaseTag) }
        val downloadPage = pageClient.fetch(OFFICIAL_DOWNLOAD_PAGE)
        val appGalleryPage = pageClient.fetch(APP_GALLERY_SEARCH)
        return descriptors.flatMap { descriptor ->
            listOf(
                githubRecord(releaseTag, descriptor, githubResult),
                metadataPageRecord(descriptor, downloadPage),
                appGalleryRecord(descriptor, appGalleryPage),
            )
        }
    }

    private fun githubRecord(
        releaseTag: String,
        descriptor: ArtifactDescriptor,
        releaseResult: Result<GitHubReleaseMetadata>,
    ): ArtifactSourceRecord {
        val release = releaseResult.getOrNull()
            ?: return unresolved(
                descriptor,
                ComponentSourceType.OFFICIAL_MICROG_GITHUB,
                "https://github.com/microg/GmsCore/releases/tag/$releaseTag",
                "GitHub API unresolved: ${releaseResult.exceptionOrNull()?.message}",
            )
        if (release.releaseTag != releaseTag) {
            return unresolved(
                descriptor,
                ComponentSourceType.OFFICIAL_MICROG_GITHUB,
                release.releaseUrl,
                "GitHub release tag mismatch: ${release.releaseTag}",
            )
        }
        val expectedAssetId = descriptor.githubAssetId
            ?: return unresolved(
                descriptor,
                ComponentSourceType.OFFICIAL_MICROG_GITHUB,
                release.releaseUrl,
                "Catalog GitHub asset ID is missing",
            )
        val matches = release.assets.filter { it.id == expectedAssetId }
        if (matches.isEmpty()) {
            return ArtifactSourceRecord(
                componentId = descriptor.componentId,
                sourceType = ComponentSourceType.OFFICIAL_MICROG_GITHUB,
                availabilityStatus = SourceAvailabilityStatus.MISSING,
                sourcePageUrl = release.releaseUrl,
                evidence = listOf("GitHub API returned zero assets for ID $expectedAssetId"),
            )
        }
        if (matches.size != 1) {
            return unresolved(
                descriptor,
                ComponentSourceType.OFFICIAL_MICROG_GITHUB,
                release.releaseUrl,
                "GitHub API returned ${matches.size} assets for ID $expectedAssetId",
            )
        }
        val asset = matches.single()
        val expectedUrl =
            "https://github.com/microg/GmsCore/releases/download/$releaseTag/${asset.name}"
        val expectedApiUrl =
            "https://api.github.com/repos/microg/GmsCore/releases/assets/${asset.id}"
        if (asset.state != "uploaded" ||
            asset.contentType != "application/vnd.android.package-archive" ||
            asset.size <= 0L ||
            asset.name != descriptor.artifactFilename ||
            asset.downloadUrl != expectedUrl ||
            (asset.apiUrl != null && asset.apiUrl != expectedApiUrl)
        ) {
            return unresolved(
                descriptor,
                ComponentSourceType.OFFICIAL_MICROG_GITHUB,
                release.releaseUrl,
                "GitHub asset metadata mismatch",
            )
        }
        val noteFilenames = releaseNoteFilenames(release.releaseNotes, descriptor.packageName)
        val notesMismatch = noteFilenames.isNotEmpty() && asset.name !in noteFilenames
        return ArtifactSourceRecord(
            componentId = descriptor.componentId,
            sourceType = ComponentSourceType.OFFICIAL_MICROG_GITHUB,
            availabilityStatus = SourceAvailabilityStatus.AVAILABLE,
            sourcePageUrl = release.releaseUrl,
            downloadUrl = asset.apiUrl ?: asset.downloadUrl,
            sourceAssetId = asset.id,
            observedFilename = asset.name,
            expectedSize = asset.size,
            sourceDigest = asset.digest,
            evidence = buildList {
                add("GitHub API returned one uploaded APK for asset ID ${asset.id}")
                if (notesMismatch) {
                    add("Release notes filenames for ${descriptor.packageName}: ${noteFilenames.joinToString()}")
                }
            },
            warningCodes = if (notesMismatch) {
                listOf("RELEASE_NOTES_ASSET_MISMATCH")
            } else {
                emptyList()
            },
        )
    }

    private fun releaseNoteFilenames(notes: String, packageName: String): Set<String> =
        Regex("${Regex.escape(packageName)}-[A-Za-z0-9._+-]+-hw\\.apk")
            .findAll(notes)
            .map { it.value }
            .toSet()

    private fun metadataPageRecord(
        descriptor: ArtifactDescriptor,
        page: OfficialPageSnapshot,
    ): ArtifactSourceRecord {
        val declared = page.body?.contains(descriptor.artifactFilename) == true
        return ArtifactSourceRecord(
            componentId = descriptor.componentId,
            sourceType = ComponentSourceType.OFFICIAL_MICROG_DOWNLOAD_PAGE,
            availabilityStatus = if (declared) {
                SourceAvailabilityStatus.METADATA_ONLY
            } else {
                SourceAvailabilityStatus.UNRESOLVED
            },
            sourcePageUrl = OFFICIAL_DOWNLOAD_PAGE,
            observedFilename = if (declared) descriptor.artifactFilename else null,
            evidence = listOfNotNull(
                page.error,
                page.finalUrl?.let { "Official download page resolved to $it" },
                if (declared) "Page declares the exact filename but exposes no independent binary" else null,
            ),
        )
    }

    private fun appGalleryRecord(
        descriptor: ArtifactDescriptor,
        page: OfficialPageSnapshot,
    ): ArtifactSourceRecord {
        val declared = page.body?.let { body ->
            body.contains(descriptor.artifactFilename) || body.contains(descriptor.packageName)
        } == true
        return ArtifactSourceRecord(
            componentId = descriptor.componentId,
            sourceType = ComponentSourceType.OFFICIAL_HUAWEI_APPGALLERY,
            availabilityStatus = if (declared) {
                SourceAvailabilityStatus.METADATA_ONLY
            } else {
                SourceAvailabilityStatus.UNRESOLVED
            },
            sourcePageUrl = APP_GALLERY_SEARCH,
            observedFilename = if (declared) descriptor.artifactFilename else null,
            evidence = listOfNotNull(
                page.error,
                if (!declared && page.error == null) {
                    "Public AppGallery page exposes no exact artifact metadata or binary URL"
                } else {
                    null
                },
            ),
        )
    }

    private fun unresolved(
        descriptor: ArtifactDescriptor,
        sourceType: ComponentSourceType,
        sourcePageUrl: String,
        evidence: String,
    ) = ArtifactSourceRecord(
        componentId = descriptor.componentId,
        sourceType = sourceType,
        availabilityStatus = SourceAvailabilityStatus.UNRESOLVED,
        sourcePageUrl = sourcePageUrl,
        evidence = listOf(evidence),
    )

    private companion object {
        const val OFFICIAL_DOWNLOAD_PAGE = "https://microg.org/dl/"
        const val APP_GALLERY_SEARCH = "https://appgallery.huawei.com/search/microG"
    }
}

class HttpOfficialPageClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : OfficialPageClient {
    override fun fetch(url: String): OfficialPageSnapshot = try {
        val response = httpClient.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "global-app-compat-assistant-catalog-audit")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() in 200..299) {
            OfficialPageSnapshot(response.uri().toString(), response.body(), null)
        } else {
            OfficialPageSnapshot(response.uri().toString(), null, "HTTP ${response.statusCode()}")
        }
    } catch (failure: Exception) {
        OfficialPageSnapshot(null, null, failure.message ?: failure.javaClass.simpleName)
    }
}

class CurlOfficialArtifactDownloader(
    private val connectTimeoutSeconds: Int = 20,
    private val readStallTimeoutSeconds: Int = 60,
    private val attemptTimeoutSeconds: Int = 300,
    private val maxAttempts: Int = 3,
    private val curlExecutable: String =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "curl.exe"
        } else {
            "curl"
        },
) : OfficialArtifactDownloader {
    override fun download(record: ArtifactSourceRecord, destination: Path) {
        require(maxAttempts in 1..3) { "Download attempts must be between 1 and 3" }
        require(connectTimeoutSeconds > 0)
        require(readStallTimeoutSeconds > 0)
        require(attemptTimeoutSeconds > 0)
        require(record.availabilityStatus == SourceAvailabilityStatus.AVAILABLE)
        val url = requireNotNull(record.downloadUrl)
        require(OfficialSourceUrlPolicy.isAllowed(record.sourceType, url)) {
            "Download URL is not allowed for ${record.sourceType}: $url"
        }
        Files.createDirectories(destination.parent)
        Files.deleteIfExists(destination)
        val temporary = destination.resolveSibling("${destination.fileName}.part")
        Files.deleteIfExists(temporary)
        var lastFailure: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                Files.deleteIfExists(temporary)
                val process = ProcessBuilder(
                    curlExecutable,
                    "--fail",
                    "--location",
                    "--silent",
                    "--show-error",
                    "--proto",
                    "=https",
                    "--max-redirs",
                    "5",
                    "--connect-timeout",
                    connectTimeoutSeconds.toString(),
                    "--speed-limit",
                    "1",
                    "--speed-time",
                    readStallTimeoutSeconds.toString(),
                    "--max-time",
                    attemptTimeoutSeconds.toString(),
                    "--header",
                    "Accept: application/octet-stream",
                    "--header",
                    "User-Agent: global-app-compat-assistant-catalog-audit",
                    "--output",
                    temporary.toString(),
                    url,
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val exitCode = process.waitFor()
                check(exitCode == 0) {
                    "curl failed with exit code $exitCode: $output"
                }
                record.expectedSize?.let { expectedSize ->
                    val actualSize = Files.size(temporary)
                    check(actualSize == expectedSize) {
                        "Incomplete download: received $actualSize bytes, expected $expectedSize"
                    }
                }
                try {
                    Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                }
                return
            } catch (failure: Exception) {
                lastFailure = failure
                Files.deleteIfExists(temporary)
                Files.deleteIfExists(destination)
                if (failure is InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw failure
                }
                if (attempt + 1 == maxAttempts) return@repeat
            }
        }
        error(
            "Official artifact download failed after $maxAttempts attempts: " +
                (lastFailure?.message ?: "unknown failure"),
        )
    }
}

object OfficialSourceUrlPolicy {
    fun isAllowed(sourceType: ComponentSourceType, url: String): Boolean {
        val uri = runCatching { URI.create(url) }.getOrNull() ?: return false
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return when (sourceType) {
            ComponentSourceType.OFFICIAL_MICROG_GITHUB ->
                (host == "github.com" && uri.path.startsWith("/microg/GmsCore/releases/download/")) ||
                    (host == "api.github.com" &&
                        GITHUB_ASSET_API_PATH.matches(uri.path))
            ComponentSourceType.OFFICIAL_MICROG_DOWNLOAD_PAGE -> host == "microg.org"
            ComponentSourceType.OFFICIAL_HUAWEI_APPGALLERY ->
                host == "appgallery.huawei.com" || host.endsWith(".hicloud.com") ||
                    host.endsWith(".dbankcloud.com")
        }
    }

    private val GITHUB_ASSET_API_PATH =
        Regex("/repos/microg/GmsCore/releases/assets/\\d+")
}
