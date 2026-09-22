package com.example.globalcompat.catalog

import java.security.MessageDigest

fun ComponentCatalog.asTestSnapshot(): CatalogSnapshot {
    val seed = "test-fixture:$schemaVersion:$catalogVersion".toByteArray()
    val digest = MessageDigest.getInstance("SHA-256").digest(seed)
        .joinToString("") { byte -> "%02x".format(byte) }
    return CatalogSnapshot(
        catalogVersion = catalogVersion,
        catalogDigest = digest,
        source = CatalogSnapshotSource.BUILT_IN,
        schemaVersion = schemaVersion,
        loadedAt = 1L,
        catalog = this,
    )
}
