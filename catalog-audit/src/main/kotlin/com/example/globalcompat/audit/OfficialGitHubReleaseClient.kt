package com.example.globalcompat.audit

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OfficialGitHubReleaseClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : GitHubReleaseClient {
    override fun getRelease(releaseTag: String): GitHubReleaseMetadata {
        val uri = URI.create("https://api.github.com/repos/microg/GmsCore/releases/tags/$releaseTag")
        val request = HttpRequest.newBuilder(uri)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "global-app-compat-assistant-catalog-audit")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "GitHub release API returned HTTP ${response.statusCode()}"
        }
        return parseRelease(JsonParser.parseString(response.body()).asJsonObject)
    }

    override fun listReleases(): List<GitHubReleaseMetadata> {
        val uri = URI.create("https://api.github.com/repos/microg/GmsCore/releases?per_page=100&page=1")
        val request = request(uri)
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "GitHub releases API returned HTTP ${response.statusCode()}"
        }
        return JsonParser.parseString(response.body()).asJsonArray.map { element ->
            parseRelease(element.asJsonObject)
        }
    }

    private fun request(uri: URI): HttpRequest = HttpRequest.newBuilder(uri)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "global-app-compat-assistant-catalog-audit")
        .timeout(Duration.ofSeconds(30))
        .GET()
        .build()

    private fun parseRelease(root: com.google.gson.JsonObject): GitHubReleaseMetadata =
        GitHubReleaseMetadata(
            releaseTag = root.get("tag_name").asString,
            releaseUrl = root.get("html_url").asString,
            releaseNotes = root.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            publishedAt = root.get("published_at")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            draft = root.get("draft").asBoolean,
            prerelease = root.get("prerelease").asBoolean,
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
