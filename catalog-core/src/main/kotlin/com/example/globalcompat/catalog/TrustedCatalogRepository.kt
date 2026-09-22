package com.example.globalcompat.catalog

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

enum class CatalogSnapshotSource {
    BUILT_IN,
    REMOTE_VERIFIED,
}

data class CatalogSnapshot(
    val catalogVersion: Long,
    val catalogDigest: String,
    val source: CatalogSnapshotSource,
    val schemaVersion: Int,
    val loadedAt: Long,
    val catalog: ComponentCatalog,
)

internal data class VerifiedRemoteCatalog(
    val catalogJson: ByteArray,
    val detachedSignature: ByteArray,
    val catalog: ComponentCatalog,
)

class TrustedCatalogRepository private constructor(
    initial: TrustedEntry?,
    private val clock: () -> Long,
    private val codec: CompatibilityCatalogCodec = CompatibilityCatalogCodec(),
) {
    private val active = AtomicReference(initial)
    private val previous = AtomicReference<TrustedEntry?>(null)

    fun currentSnapshot(): CatalogSnapshot? {
        val current = active.get() ?: return null
        if (current.isValid(codec)) return current.snapshot
        val fallback = previous.get()?.takeIf { it.isValid(codec) }
        active.compareAndSet(current, fallback)
        return fallback?.snapshot
    }

    @Synchronized
    internal fun activateVerifiedRemote(verified: VerifiedRemoteCatalog): CatalogSnapshot? {
        val trustedBytes = verified.catalogJson.copyOf()
        val decoded = (codec.decode(trustedBytes) as? CatalogDecodeResult.Success)?.catalog
            ?: return currentSnapshot()
        if (decoded != verified.catalog) return currentSnapshot()
        val trustedCurrent = currentSnapshot()
        if (trustedCurrent != null && decoded.catalogVersion <= trustedCurrent.catalogVersion) {
            return trustedCurrent
        }
        val snapshot = snapshot(
            catalog = decoded,
            catalogJson = trustedBytes,
            source = CatalogSnapshotSource.REMOTE_VERIFIED,
            loadedAt = clock(),
        )
        val next = TrustedEntry(snapshot, trustedBytes)
        val current = active.get()
        previous.set(current?.takeIf { it.isValid(codec) } ?: previous.get())
        active.set(next)
        return snapshot
    }

    private data class TrustedEntry(
        val snapshot: CatalogSnapshot,
        val catalogJson: ByteArray,
    ) {
        fun isValid(codec: CompatibilityCatalogCodec): Boolean {
            if (sha256(catalogJson) != snapshot.catalogDigest) return false
            val decoded = (codec.decode(catalogJson) as? CatalogDecodeResult.Success)?.catalog
                ?: return false
            return decoded == snapshot.catalog &&
                decoded.catalogVersion == snapshot.catalogVersion &&
                decoded.schemaVersion == snapshot.schemaVersion
        }
    }

    companion object {
        fun withBuiltIn(
            clock: () -> Long = System::currentTimeMillis,
        ): TrustedCatalogRepository {
            val catalogJson = BuiltInComponentCatalog.catalogJsonBytes()
            val catalog = BuiltInComponentCatalog.catalog
            return TrustedCatalogRepository(
                initial = TrustedEntry(
                    snapshot = snapshot(
                        catalog = catalog,
                        catalogJson = catalogJson,
                        source = CatalogSnapshotSource.BUILT_IN,
                        loadedAt = clock(),
                    ),
                    catalogJson = catalogJson.copyOf(),
                ),
                clock = clock,
            )
        }

        fun withoutTrustedCatalog(
            clock: () -> Long = System::currentTimeMillis,
        ): TrustedCatalogRepository = TrustedCatalogRepository(null, clock)

        private fun snapshot(
            catalog: ComponentCatalog,
            catalogJson: ByteArray,
            source: CatalogSnapshotSource,
            loadedAt: Long,
        ) = CatalogSnapshot(
            catalogVersion = catalog.catalogVersion,
            catalogDigest = sha256(catalogJson),
            source = source,
            schemaVersion = catalog.schemaVersion,
            loadedAt = loadedAt,
            catalog = catalog,
        )
    }
}

object RuntimeTrustedCatalogRepository {
    val instance: TrustedCatalogRepository by lazy(TrustedCatalogRepository::withBuiltIn)
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }
