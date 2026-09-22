package com.example.globalcompat.catalog

class CatalogUpdateManager(
    private val repository: TrustedCatalogRepository,
    private val signatureVerifier: CatalogSignatureVerifier,
    private val store: TrustedCatalogStore,
    private val clientVersion: Long,
    private val codec: CompatibilityCatalogCodec = CompatibilityCatalogCodec(),
) {
    private val validator = CatalogSchemaValidator(clientVersion)
    private var storedSnapshotLoaded = false

    @Synchronized
    fun current(): CatalogRuntimeState {
        if (!storedSnapshotLoaded) {
            storedSnapshotLoaded = true
            val stored = store.load()
            if (stored != null) {
                val verified = verifiedSnapshot(stored)
                    ?: return state().rejected(
                        CatalogUpdateStatus.REMOTE_REJECTED,
                        "Stored remote catalog failed verification",
                    )
                val current = repository.currentSnapshot()
                if (current != null && verified.catalog.catalogVersion <= current.catalogVersion) {
                    return state().rejected(
                        CatalogUpdateStatus.ROLLBACK_BLOCKED,
                        "Stored catalog is not newer than the active trusted catalog",
                    )
                }
                repository.activateVerifiedRemote(verified)
            }
        }
        return state()
    }

    @Synchronized
    fun refresh(source: CatalogPackageSource): CatalogRuntimeState {
        val current = current()
        val remote = runCatching { source.fetch() }.getOrNull() ?: return current
        if (!signatureVerifier.verify(remote.catalogJson, remote.detachedSignature)) {
            return current.rejected(CatalogUpdateStatus.REMOTE_REJECTED, "Catalog signature rejected")
        }
        val decoded = when (val result = codec.decode(remote.catalogJson)) {
            is CatalogDecodeResult.Success -> result.catalog
            is CatalogDecodeResult.UnsupportedSchema -> {
                return current.rejected(
                    CatalogUpdateStatus.SCHEMA_UNSUPPORTED,
                    "Unsupported schemaVersion: ${result.observedSchemaVersion}",
                )
            }
            is CatalogDecodeResult.Invalid -> {
                return current.rejected(CatalogUpdateStatus.REMOTE_REJECTED, result.reason)
            }
        }
        val validation = runCatching { validator.validate(decoded) }.getOrElse {
            return current.rejected(
                CatalogUpdateStatus.REMOTE_REJECTED,
                "Catalog structure is incomplete",
            )
        }
        if (!validation.isValid) {
            val status = if (validation.errors.any { it.contains("minClientVersion") }) {
                CatalogUpdateStatus.SCHEMA_UNSUPPORTED
            } else {
                CatalogUpdateStatus.REMOTE_REJECTED
            }
            return current.rejected(status, validation.errors.joinToString("; "))
        }
        val active = repository.currentSnapshot()
        if (active != null && decoded.catalogVersion <= active.catalogVersion) {
            return current.rejected(
                CatalogUpdateStatus.ROLLBACK_BLOCKED,
                "Catalog downgrade or same-version replacement is forbidden",
            )
        }
        val stored = TrustedCatalogSnapshot(
            remote.catalogJson.copyOf(),
            remote.detachedSignature.copyOf(),
        )
        if (runCatching { store.save(stored) }.isFailure) {
            return current.rejected(
                CatalogUpdateStatus.REMOTE_REJECTED,
                "Verified catalog could not be stored",
            )
        }
        repository.activateVerifiedRemote(
            VerifiedRemoteCatalog(
                catalogJson = remote.catalogJson,
                detachedSignature = remote.detachedSignature,
                catalog = decoded,
            ),
        )
        return state().copy(
            status = CatalogUpdateStatus.REMOTE_VERIFIED,
            detail = "Remote catalog signature and schema verified",
        )
    }

    private fun verifiedSnapshot(snapshot: TrustedCatalogSnapshot): VerifiedRemoteCatalog? {
        if (!signatureVerifier.verify(snapshot.catalogJson, snapshot.detachedSignature)) return null
        val catalog = (codec.decode(snapshot.catalogJson) as? CatalogDecodeResult.Success)?.catalog
            ?: return null
        val valid = runCatching { validator.validate(catalog).isValid }.getOrDefault(false)
        return catalog.takeIf { valid }?.let {
            VerifiedRemoteCatalog(snapshot.catalogJson, snapshot.detachedSignature, it)
        }
    }

    private fun state(): CatalogRuntimeState {
        val snapshot = repository.currentSnapshot()
        return CatalogRuntimeState(
            activeSnapshot = snapshot,
            status = when (snapshot?.source) {
                CatalogSnapshotSource.BUILT_IN -> CatalogUpdateStatus.BUILT_IN
                CatalogSnapshotSource.REMOTE_VERIFIED -> CatalogUpdateStatus.REMOTE_VERIFIED
                null -> CatalogUpdateStatus.REMOTE_REJECTED
            },
            detail = when (snapshot?.source) {
                CatalogSnapshotSource.BUILT_IN -> "Using APK built-in catalog"
                CatalogSnapshotSource.REMOTE_VERIFIED -> "Using verified remote catalog"
                null -> "No trusted catalog is available"
            },
        )
    }

    private fun CatalogRuntimeState.rejected(
        rejectionStatus: CatalogUpdateStatus,
        reason: String,
    ) = copy(status = rejectionStatus, detail = reason)
}
