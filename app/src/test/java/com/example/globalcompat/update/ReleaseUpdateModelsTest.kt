package com.example.globalcompat.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdateModelsTest {
    @Test
    fun `new stable version is newer than current release candidate`() {
        assertTrue(ReleaseVersionComparator.isNewer("0.1.0", "0.1.0-rc6"))
    }

    @Test
    fun `newer release candidate is detected`() {
        assertTrue(ReleaseVersionComparator.isNewer("0.1.0-rc7", "0.1.0-rc6"))
        assertFalse(ReleaseVersionComparator.isNewer("0.1.0-rc5", "0.1.0-rc6"))
    }

    @Test
    fun `daily gate performs at most one request`() {
        val store = FakeStore()
        var calls = 0
        val checker = DailyReleaseUpdateChecker(
            store = store,
            source = LatestReleaseSource { calls += 1; release("0.2.0") },
            now = { 1_000_000L },
        )

        assertEquals(ReleaseUpdateStatus.UPDATE_AVAILABLE, checker.checkIfDue("0.1.0").status)
        assertEquals(ReleaseUpdateStatus.UPDATE_AVAILABLE, checker.checkIfDue("0.1.0").status)
        assertEquals(1, calls)
    }

    @Test
    fun `request becomes due after twenty four hours`() {
        val store = FakeStore()
        var time = 1_000_000L
        var calls = 0
        val checker = DailyReleaseUpdateChecker(
            store,
            LatestReleaseSource { calls += 1; release("0.2.0") },
            now = { time },
        )
        checker.checkIfDue("0.1.0")
        time += DailyReleaseUpdateChecker.CHECK_INTERVAL_MILLIS
        checker.checkIfDue("0.1.0")
        assertEquals(2, calls)
    }

    @Test
    fun `network failure preserves cached update`() {
        val store = FakeStore(cached = release("0.2.0"))
        val checker = DailyReleaseUpdateChecker(
            store,
            LatestReleaseSource { error("offline") },
            now = { 10L },
        )
        val result = checker.checkIfDue("0.1.0")
        assertEquals(ReleaseUpdateStatus.UPDATE_AVAILABLE, result.status)
        assertEquals("0.2.0", result.release?.versionName)
    }

    @Test
    fun `network failure without cache does not block app`() {
        val checker = DailyReleaseUpdateChecker(
            FakeStore(),
            LatestReleaseSource { error("offline") },
            now = { 10L },
        )
        assertEquals(ReleaseUpdateStatus.UNAVAILABLE, checker.checkIfDue("0.1.0").status)
    }

    @Test
    fun `GitHub parser accepts one official APK with digest`() {
        val parsed = GitHubLatestReleaseParser.parse(githubJson())
        assertEquals("0.2.0", parsed.versionName)
        assertEquals(77L, parsed.asset.assetId)
        assertEquals("a".repeat(64), parsed.asset.sha256)
    }

    @Test(expected = IllegalStateException::class)
    fun `GitHub parser rejects missing digest`() {
        GitHubLatestReleaseParser.parse(githubJson().replace(
            "\"digest\":\"sha256:${"a".repeat(64)}\"",
            "\"digest\":null",
        ))
    }

    @Test(expected = IllegalStateException::class)
    fun `GitHub parser rejects multiple APK assets`() {
        val second = """{"id":78,"name":"other.apk","browser_download_url":"https://github.com/acme/app/releases/download/v0.2.0/other.apk","size":8,"digest":"sha256:${"b".repeat(64)}"}"""
        GitHubLatestReleaseParser.parse(githubJson().replace(
            "  ]",
            "    ,$second\n  ]",
        ))
    }

    @Test(expected = IllegalStateException::class)
    fun `GitHub parser rejects non GitHub APK source`() {
        GitHubLatestReleaseParser.parse(githubJson().replace("https://github.com/", "https://example.com/"))
    }

    @Test
    fun `verified release APK requires exact hash package version and signer`() {
        ReleaseApkVerificationPolicy.requireValid(
            release = release("0.2.0"),
            expectedPackageName = "com.example.globalcompat",
            currentVersionCode = 2,
            expectedSignerSha256 = "c".repeat(64),
            actual = VerifiedReleaseApk(
                packageName = "com.example.globalcompat",
                versionCode = 3,
                versionName = "0.2.0",
                sha256 = "a".repeat(64),
                signerSha256 = "c".repeat(64),
            ),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `signer mismatch fails closed`() {
        ReleaseApkVerificationPolicy.requireValid(
            release = release("0.2.0"),
            expectedPackageName = "com.example.globalcompat",
            currentVersionCode = 2,
            expectedSignerSha256 = "c".repeat(64),
            actual = VerifiedReleaseApk(
                packageName = "com.example.globalcompat",
                versionCode = 3,
                versionName = "0.2.0",
                sha256 = "a".repeat(64),
                signerSha256 = "d".repeat(64),
            ),
        )
    }

    private fun githubJson(): String = """
        {
          "tag_name":"v0.2.0",
          "name":"Version 0.2.0",
          "body":"Safe update",
          "published_at":"2026-09-24T00:00:00Z",
          "draft":false,
          "prerelease":false,
          "assets":[
            {
              "id":77,
              "name":"app-release.apk",
              "browser_download_url":"https://github.com/acme/app/releases/download/v0.2.0/app-release.apk",
              "size":8,
              "digest":"sha256:${"a".repeat(64)}"
            }
          ]
        }
    """.trimIndent()

    private fun release(version: String) = AvailableRelease(
        tagName = "v$version",
        versionName = version,
        title = version,
        notes = "notes",
        publishedAt = "2026-09-24T00:00:00Z",
        asset = ReleaseApkAsset(
            assetId = 1,
            filename = "app-release.apk",
            downloadUrl = "https://github.com/acme/app/releases/download/v$version/app-release.apk",
            sizeBytes = 1,
            sha256 = "a".repeat(64),
        ),
    )

    private class FakeStore(
        private var lastAttempt: Long? = null,
        private var cached: AvailableRelease? = null,
    ) : UpdateCheckStateStore {
        override fun lastAttemptAt(): Long? = lastAttempt
        override fun recordAttempt(atMillis: Long) { lastAttempt = atMillis }
        override fun cachedRelease(): AvailableRelease? = cached
        override fun saveRelease(release: AvailableRelease?) { cached = release }
    }
}
