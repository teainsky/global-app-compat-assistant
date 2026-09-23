package com.example.globalcompat.installation

import com.example.globalcompat.catalog.CatalogSnapshot
import com.example.globalcompat.catalog.TrustedCatalogSnapshotProvider
import javax.crypto.spec.SecretKeySpec

internal fun testInstallAuthorizationSealer(): InstallAuthorizationSealer =
    HmacSha256InstallAuthorizationSealer {
        SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "HmacSHA256")
    }

internal class MutableCatalogSnapshotProvider(
    var snapshot: CatalogSnapshot?,
) : TrustedCatalogSnapshotProvider {
    override fun currentSnapshot(): CatalogSnapshot? = snapshot
}
