package com.example.globalcompat.catalog

class CatalogUpdateManager(
    private val builtInCatalog: ComponentCatalog,
    private val signatureVerifier: CatalogSignatureVerifier,
    private val store: TrustedCatalogStore,
    private val clientVersion: Long,
    private val codec: CompatibilityCatalogCodec = CompatibilityCatalogCodec(),
) {
    private val validator = CatalogSchemaValidator(clientVersion)

    fun current(): CatalogRuntimeState {
        val stored = store.load() ?: return builtInState()
        val verifiedCatalog = verifiedSnapshot(stored)
            ?: return builtInState().rejected(
                CatalogUpdateStatus.REMOTE_REJECTED,
                "Stored remote catalog failed verification",
            )
        return verifiedCatalog.let { catalog ->
            if (catalog.catalogVersion >= builtInCatalog.catalogVersion) {
                CatalogRuntimeState(catalog, CatalogUpdateStatus.REMOTE_VERIFIED, "Stored signed catalog")
            } else {
                builtInState().rejected(
                    CatalogUpdateStatus.ROLLBACK_BLOCKED,
                    "Stored catalog is older than the built-in catalog",
                )
            }
        }
    }

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
        if (decoded.catalogVersion <= current.activeCatalog.catalogVersion) {
            return current.rejected(
                CatalogUpdateStatus.ROLLBACK_BLOCKED,
                "Catalog downgrade or same-version replacement is forbidden",
            )
        }
        store.save(TrustedCatalogSnapshot(remote.catalogJson.copyOf(), remote.detachedSignature.copyOf()))
        return CatalogRuntimeState(
            activeCatalog = decoded,
            status = CatalogUpdateStatus.REMOTE_VERIFIED,
            detail = "Remote catalog signature and schema verified",
        )
    }

    private fun verifiedSnapshot(snapshot: TrustedCatalogSnapshot): ComponentCatalog? {
        if (!signatureVerifier.verify(snapshot.catalogJson, snapshot.detachedSignature)) return null
        val catalog = (codec.decode(snapshot.catalogJson) as? CatalogDecodeResult.Success)?.catalog
            ?: return null
        return runCatching { catalog.takeIf { validator.validate(it).isValid } }.getOrNull()
    }

    private fun builtInState() = CatalogRuntimeState(
        activeCatalog = builtInCatalog,
        status = CatalogUpdateStatus.BUILT_IN,
        detail = "Using APK built-in catalog",
    )

    private fun CatalogRuntimeState.rejected(
        rejectionStatus: CatalogUpdateStatus,
        reason: String,
    ) = copy(status = rejectionStatus, detail = reason)
}
