package com.example.globalcompat.catalog

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets

sealed interface CatalogDecodeResult {
    data class Success(val catalog: ComponentCatalog) : CatalogDecodeResult

    data class UnsupportedSchema(val observedSchemaVersion: Int?) : CatalogDecodeResult

    data class Invalid(val reason: String) : CatalogDecodeResult
}

class CompatibilityCatalogCodec(
    private val gson: Gson = Gson(),
) {
    fun decode(bytes: ByteArray): CatalogDecodeResult {
        val json = bytes.toString(StandardCharsets.UTF_8)
        val root = runCatching { JsonParser.parseString(json).asJsonObject }
            .getOrElse { return CatalogDecodeResult.Invalid("Catalog JSON is malformed") }
        val schemaVersion = runCatching { root.get("schemaVersion")?.asInt }.getOrNull()
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return CatalogDecodeResult.UnsupportedSchema(schemaVersion)
        }
        return runCatching {
            gson.fromJson(root, ComponentCatalog::class.java)
                .applyCompatibilityRecords()
        }.fold(
            onSuccess = { CatalogDecodeResult.Success(it) },
            onFailure = { CatalogDecodeResult.Invalid("Catalog fields are invalid") },
        )
    }

    private fun ComponentCatalog.applyCompatibilityRecords(): ComponentCatalog {
        val releaseRecords = compatibilityRecords
            .filter { it.componentId == null }
            .associateBy { it.releaseId }
        val artifactRecords = compatibilityRecords
            .filter { it.componentId != null }
            .associateBy { it.releaseId to it.componentId }
        return copy(
            releases = releases.map { release ->
                release.copy(
                    compatibilityStatus = releaseRecords[release.releaseId]?.status
                        ?: release.compatibilityStatus,
                    artifacts = release.artifacts.map { artifact ->
                        val record = artifactRecords[release.releaseId to artifact.componentId]
                        artifact.copy(
                            compatibilityStatus = record?.status ?: artifact.compatibilityStatus,
                            verifiedDeviceFamilies = record?.verifiedDeviceFamilies
                                ?: artifact.verifiedDeviceFamilies,
                            verifiedSystemVersions = record?.verifiedSystemVersions
                                ?: artifact.verifiedSystemVersions,
                        )
                    },
                )
            },
        )
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 5
    }
}
