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
        val matches = release.assets.filter { it.name == descriptor.artifactFilename }
        val declared = release.releaseNotes.contains(descriptor.artifactFilename)
        if (matches.isEmpty()) {
            return ArtifactSourceRecord(
                componentId = descriptor.componentId,
                sourceType = ComponentSourceType.OFFICIAL_MICROG_GITHUB,
                availabilityStatus = SourceAvailabilityStatus.MISSING,
                sourcePageUrl = release.releaseUrl,
                observedFilename = descriptor.artifactFilename,
                evidence = listOfNotNull(
                    "GitHub API returned zero exact-name assets",
                    if (declared) "Release notes declare the artifact" else null,
                ),
            )
        }
        if (matches.size != 1) {
            return unresolved(
                descriptor,
                ComponentSourceType.OFFICIAL_MICROG_GITHUB,
                release.releaseUrl,
                "GitHub API returned ${matches.size} exact-name assets",
            )
        }
        val asset = matches.single()
        val expectedUrl =
            "https://github.com/microg/GmsCore/releases/download/$releaseTag/${asset.name}"
        if (asset.state != "uploaded" ||
            asset.contentType != "application/vnd.android.package-archive" ||
            asset.size <= 0L ||
            asset.downloadUrl != expectedUrl
        ) {
            return unresolved(
                descriptor,
                ComponentSourceType.OFFICIAL_MICROG_GITHUB,
                release.releaseUrl,
                "GitHub asset metadata mismatch",
            )
        }
        return ArtifactSourceRecord(
            componentId = descriptor.componentId,
            sourceType = ComponentSourceType.OFFICIAL_MICROG_GITHUB,
            availabilityStatus = SourceAvailabilityStatus.AVAILABLE,
            sourcePageUrl = release.releaseUrl,
            downloadUrl = asset.downloadUrl,
            sourceAssetId = asset.id.toString(),
            observedFilename = asset.name,
            expectedSize = asset.size,
            sourceDigest = asset.digest,
            evidence = listOf("GitHub API returned one exact-name uploaded APK asset"),
        )
    }

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

class HttpOfficialArtifactDownloader(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : OfficialArtifactDownloader {
    override fun download(record: ArtifactSourceRecord, destination: Path) {
        require(record.availabilityStatus == SourceAvailabilityStatus.AVAILABLE)
        val url = requireNotNull(record.downloadUrl)
        require(OfficialSourceUrlPolicy.isAllowed(record.sourceType, url)) {
            "Download URL is not allowed for ${record.sourceType}: $url"
        }
        Files.createDirectories(destination.parent)
        Files.deleteIfExists(destination)
        try {
            val response = httpClient.send(
                HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "global-app-compat-assistant-catalog-audit")
                    .timeout(Duration.ofMinutes(3))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofFile(destination),
            )
            check(response.statusCode() in 200..299) {
                "Official artifact download returned HTTP ${response.statusCode()}"
            }
        } catch (failure: Exception) {
            Files.deleteIfExists(destination)
            throw failure
        }
    }
}

object OfficialSourceUrlPolicy {
    fun isAllowed(sourceType: ComponentSourceType, url: String): Boolean {
        val uri = runCatching { URI.create(url) }.getOrNull() ?: return false
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return when (sourceType) {
            ComponentSourceType.OFFICIAL_MICROG_GITHUB ->
                host == "github.com" && uri.path.startsWith("/microg/GmsCore/releases/download/")
            ComponentSourceType.OFFICIAL_MICROG_DOWNLOAD_PAGE -> host == "microg.org"
            ComponentSourceType.OFFICIAL_HUAWEI_APPGALLERY ->
                host == "appgallery.huawei.com" || host.endsWith(".hicloud.com") ||
                    host.endsWith(".dbankcloud.com")
        }
    }
}
