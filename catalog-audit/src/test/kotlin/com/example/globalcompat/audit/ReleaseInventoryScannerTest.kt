package com.example.globalcompat.audit

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInventoryScannerTest {
    @Test
    fun `latest incomplete release is skipped for candidate selection`() {
        val report = scanner(
            release("v2", "2026-02-02T00:00:00Z", assets = listOf(gmsAsset("2", 201))),
            release("v1", "2026-01-01T00:00:00Z", assets = pair("1", 101, 102)),
        ).scan()

        assertFalse(report.releases.first().completeHuaweiPair)
        assertEquals("v1", report.latestCompleteHuaweiPair?.releaseTag)
    }

    @Test
    fun `older complete pair becomes audit candidate with real asset ids`() {
        val report = scanner(
            release("v3", "2026-03-01T00:00:00Z", assets = emptyList()),
            release("v2", "2026-02-01T00:00:00Z", assets = pair("2", 201, 202)),
        ).scan()

        val candidate = requireNotNull(report.latestCompleteHuaweiPair)
        assertEquals(InventoryCandidateStatus.AUDIT_CANDIDATE, candidate.status)
        assertEquals("com.google.android.gms-2-hw.apk", candidate.huaweiGmsCoreAsset.filename)
        assertEquals(201L, candidate.huaweiGmsCoreAsset.assetId)
        assertEquals("com.android.vending-2-hw.apk", candidate.huaweiCompanionAsset.filename)
        assertEquals(202L, candidate.huaweiCompanionAsset.assetId)
    }

    @Test
    fun `draft and prerelease entries do not participate`() {
        val report = scanner(
            release("draft", "2026-04-01T00:00:00Z", pair("d", 1, 2), draft = true),
            release("preview", "2026-03-01T00:00:00Z", pair("p", 3, 4), prerelease = true),
            release("stable", "2026-02-01T00:00:00Z", pair("s", 5, 6)),
        ).scan()

        assertEquals(listOf("stable"), report.releases.map { it.releaseTag })
        assertEquals("stable", report.latestCompleteHuaweiPair?.releaseTag)
    }

    @Test
    fun `release notes cannot invent missing assets`() {
        val notes = "Install com.google.android.gms-9-hw.apk and com.android.vending-9-hw.apk"
        val report = scanner(
            release("v9", "2026-09-01T00:00:00Z", assets = emptyList(), releaseNotes = notes),
        ).scan()

        assertFalse(report.releases.single().completeHuaweiPair)
        assertNull(report.releases.single().huaweiGmsCoreAsset)
        assertNull(report.releases.single().huaweiCompanionAsset)
        assertNull(report.latestCompleteHuaweiPair)
    }

    @Test
    fun `all incomplete stable releases fail closed`() {
        val report = scanner(
            release("v2", "2026-02-01T00:00:00Z", listOf(gmsAsset("2", 1))),
            release("v1", "2026-01-01T00:00:00Z", listOf(companionAsset("1", 2))),
        ).scan()

        assertTrue(report.releases.none { it.completeHuaweiPair })
        assertNull(report.latestCompleteHuaweiPair)
        assertEquals("NO_COMPLETE_HUAWEI_PAIR", report.failureCode)
    }

    private fun scanner(vararg releases: GitHubReleaseMetadata) = ReleaseInventoryScanner(
        github = FakeGitHubClient(releases.toList()),
        clock = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC),
    )

    private fun release(
        tag: String,
        publishedAt: String,
        assets: List<GitHubAssetMetadata>,
        draft: Boolean = false,
        prerelease: Boolean = false,
        releaseNotes: String = "",
    ) = GitHubReleaseMetadata(
        releaseTag = tag,
        releaseUrl = "https://github.com/microg/GmsCore/releases/tag/$tag",
        releaseNotes = releaseNotes,
        assets = assets,
        publishedAt = publishedAt,
        draft = draft,
        prerelease = prerelease,
    )

    private fun pair(version: String, gmsId: Long, companionId: Long) = listOf(
        gmsAsset(version, gmsId),
        companionAsset(version, companionId),
    )

    private fun gmsAsset(version: String, id: Long) =
        asset("com.google.android.gms-$version-hw.apk", id)

    private fun companionAsset(version: String, id: Long) =
        asset("com.android.vending-$version-hw.apk", id)

    private fun asset(filename: String, id: Long) = GitHubAssetMetadata(
        id = id,
        name = filename,
        contentType = "application/vnd.android.package-archive",
        state = "uploaded",
        size = 1,
        digest = null,
        downloadUrl = "https://github.com/microg/GmsCore/releases/download/test/$filename",
    )

    private class FakeGitHubClient(
        private val releases: List<GitHubReleaseMetadata>,
    ) : GitHubReleaseClient {
        override fun getRelease(releaseTag: String): GitHubReleaseMetadata =
            error("Not used by inventory tests")

        override fun listReleases(): List<GitHubReleaseMetadata> = releases
    }
}
