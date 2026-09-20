package com.example.globalcompat.audit

import java.time.Clock
import java.time.Instant

enum class InventoryCandidateStatus {
    AUDIT_CANDIDATE,
}

data class InventoryAsset(
    val filename: String,
    val assetId: Long,
)

data class ReleaseInventoryEntry(
    val releaseTag: String,
    val publishedAt: String,
    val huaweiGmsCoreAsset: InventoryAsset?,
    val huaweiCompanionAsset: InventoryAsset?,
    val completeHuaweiPair: Boolean,
    val assetIds: List<Long>,
)

data class HuaweiPairAuditCandidate(
    val releaseTag: String,
    val status: InventoryCandidateStatus,
    val huaweiGmsCoreAsset: InventoryAsset,
    val huaweiCompanionAsset: InventoryAsset,
)

data class ReleaseInventoryReport(
    val scannedAt: String,
    val releases: List<ReleaseInventoryEntry>,
    val latestCompleteHuaweiPair: HuaweiPairAuditCandidate?,
    val failureCode: String?,
)

class ReleaseInventoryScanner(
    private val github: GitHubReleaseClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun scan(limit: Int = 10): ReleaseInventoryReport {
        require(limit > 0)
        val releases = github.listReleases()
            .asSequence()
            .filter { !it.draft && !it.prerelease }
            .sortedByDescending { it.publishedAt }
            .take(limit)
            .map(::inventoryEntry)
            .toList()
        val latestComplete = releases.firstOrNull { it.completeHuaweiPair }?.let { release ->
            HuaweiPairAuditCandidate(
                releaseTag = release.releaseTag,
                status = InventoryCandidateStatus.AUDIT_CANDIDATE,
                huaweiGmsCoreAsset = requireNotNull(release.huaweiGmsCoreAsset),
                huaweiCompanionAsset = requireNotNull(release.huaweiCompanionAsset),
            )
        }
        return ReleaseInventoryReport(
            scannedAt = Instant.now(clock).toString(),
            releases = releases,
            latestCompleteHuaweiPair = latestComplete,
            failureCode = if (latestComplete == null) "NO_COMPLETE_HUAWEI_PAIR" else null,
        )
    }

    private fun inventoryEntry(release: GitHubReleaseMetadata): ReleaseInventoryEntry {
        val gmsAssets = release.assets.filter { asset ->
            asset.isUploadedApk() &&
                asset.name.startsWith("com.google.android.gms-") &&
                asset.name.endsWith("-hw.apk")
        }
        val companionAssets = release.assets.filter { asset ->
            asset.isUploadedApk() &&
                asset.name.startsWith("com.android.vending-") &&
                asset.name.endsWith("-hw.apk")
        }
        val gms = gmsAssets.singleOrNull()?.toInventoryAsset()
        val companion = companionAssets.singleOrNull()?.toInventoryAsset()
        return ReleaseInventoryEntry(
            releaseTag = release.releaseTag,
            publishedAt = release.publishedAt,
            huaweiGmsCoreAsset = gms,
            huaweiCompanionAsset = companion,
            completeHuaweiPair = gms != null && companion != null,
            assetIds = (gmsAssets + companionAssets).map { it.id },
        )
    }

    private fun GitHubAssetMetadata.toInventoryAsset() = InventoryAsset(name, id)

    private fun GitHubAssetMetadata.isUploadedApk(): Boolean =
        state == "uploaded" && contentType == "application/vnd.android.package-archive"
}
