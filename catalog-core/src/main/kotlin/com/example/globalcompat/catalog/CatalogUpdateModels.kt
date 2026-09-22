package com.example.globalcompat.catalog

enum class CatalogUpdateStatus {
    BUILT_IN,
    REMOTE_VERIFIED,
    REMOTE_REJECTED,
    ROLLBACK_BLOCKED,
    SCHEMA_UNSUPPORTED,
}

data class SignedCatalogPackage(
    val catalogJson: ByteArray,
    val detachedSignature: ByteArray,
)

fun interface CatalogPackageSource {
    /** Returns null when the source is offline or no response is available. */
    fun fetch(): SignedCatalogPackage?
}

data class TrustedCatalogSnapshot(
    val catalogJson: ByteArray,
    val detachedSignature: ByteArray,
)

interface TrustedCatalogStore {
    fun load(): TrustedCatalogSnapshot?

    fun save(snapshot: TrustedCatalogSnapshot)
}

data class CatalogRuntimeState(
    val activeSnapshot: CatalogSnapshot?,
    val status: CatalogUpdateStatus,
    val detail: String,
) {
    val activeCatalog: ComponentCatalog
        get() = requireNotNull(activeSnapshot) { "No trusted catalog is active" }.catalog
}
