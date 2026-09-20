package com.example.globalcompat.audit

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

class OfficialGitHubReleaseClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : GitHubReleaseClient {
    override fun getRelease(releaseTag: String): GitHubReleaseMetadata {
        val uri = URI.create("https://api.github.com/repos/microg/GmsCore/releases/tags/$releaseTag")
        val request = HttpRequest.newBuilder(uri)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "global-app-compat-assistant-catalog-audit")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "GitHub release API returned HTTP ${response.statusCode()}"
        }
        val root = JsonParser.parseString(response.body()).asJsonObject
        return GitHubReleaseMetadata(
            releaseTag = root.get("tag_name").asString,
            releaseUrl = root.get("html_url").asString,
            assets = root.getAsJsonArray("assets").map { element ->
                val asset = element.asJsonObject
                GitHubAssetMetadata(
                    id = asset.get("id").asLong,
                    name = asset.get("name").asString,
                    contentType = asset.get("content_type").asString,
                    state = asset.get("state").asString,
                    size = asset.get("size").asLong,
                    digest = asset.get("digest")?.takeUnless { it.isJsonNull }?.asString,
                    downloadUrl = asset.get("browser_download_url").asString,
                )
            },
        )
    }

    override fun download(asset: GitHubAssetMetadata, destination: Path) {
        Files.createDirectories(destination.parent)
        val request = HttpRequest.newBuilder(URI.create(asset.downloadUrl))
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "global-app-compat-assistant-catalog-audit")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(destination))
        check(response.statusCode() in 200..299) {
            "GitHub asset download returned HTTP ${response.statusCode()}"
        }
    }
}
