package com.example.globalcompat.catalog

object BuiltInComponentCatalog {
    val catalog: ComponentCatalog by lazy {
        val bytes = resourceBytes(CATALOG_RESOURCE_PATH)
        val signature = CatalogSignatureVerifier.decodeBase64(
            resourceBytes(SIGNATURE_RESOURCE_PATH).decodeToString(),
        )
        check(
            CatalogSignatureVerifier(
                CatalogSignatureVerifier.decodePublicKey(PINNED_PUBLIC_KEY_BASE64),
            ).verify(bytes, signature),
        ) { "Built-in compatibility catalog signature is invalid" }
        val decoded = CompatibilityCatalogCodec().decode(bytes)
        val parsed = (decoded as? CatalogDecodeResult.Success)?.catalog
            ?: error("Built-in compatibility catalog cannot be decoded: $decoded")
        val validation = CatalogSchemaValidator(CLIENT_VERSION).validate(parsed)
        check(validation.isValid) {
            "Built-in compatibility catalog is unsafe: ${validation.errors.joinToString()}"
        }
        parsed
    }

    internal fun catalogJsonBytes(): ByteArray = resourceBytes(CATALOG_RESOURCE_PATH)

    private fun resourceBytes(path: String): ByteArray = checkNotNull(
        BuiltInComponentCatalog::class.java.getResourceAsStream(path),
    ) { "Built-in compatibility catalog resource is missing: $path" }.use { it.readBytes() }

    const val CLIENT_VERSION = 1L
    private const val CATALOG_RESOURCE_PATH = "/compatibility-catalog.json"
    private const val SIGNATURE_RESOURCE_PATH = "/compatibility-catalog.sig"
    private const val PINNED_PUBLIC_KEY_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEul7T6JFpbnOtO1phS6oFcZeCYqD4RMHrOz/PeHzxdhO9OmfVpT148QBopPpPhdyJrbsNY+q2NDxnmTuuNMpI7w=="
}
