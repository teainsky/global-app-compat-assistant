package com.example.globalcompat.catalog

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class CatalogUpdateManagerTest {
    private val gson = Gson()
    private val signingKey = newKeyPair()
    private val store = MemoryCatalogStore()
    private val manager = manager(signingKey, store)

    @Test
    fun `correctly signed newer catalog is accepted`() {
        val state = manager.refresh(source(signed(catalogVersion = 2)))

        assertEquals(CatalogUpdateStatus.REMOTE_VERIFIED, state.status)
        assertEquals(2L, state.activeCatalog.catalogVersion)
        assertEquals(2L, manager.current().activeCatalog.catalogVersion)
    }

    @Test
    fun `changing one JSON byte after signing is rejected`() {
        val original = signed(catalogVersion = 2)
        val tampered = original.catalogJson.copyOf().also { bytes ->
            val index = bytes.indexOfFirst { it == '2'.code.toByte() }
            bytes[index] = '3'.code.toByte()
        }

        val state = manager.refresh(source(original.copy(catalogJson = tampered)))

        assertEquals(CatalogUpdateStatus.REMOTE_REJECTED, state.status)
        assertEquals(1L, state.activeCatalog.catalogVersion)
    }

    @Test
    fun `signature from a different key is rejected`() {
        val state = manager.refresh(source(signed(catalogVersion = 2, keyPair = newKeyPair())))

        assertEquals(CatalogUpdateStatus.REMOTE_REJECTED, state.status)
        assertEquals(1L, state.activeCatalog.catalogVersion)
    }

    @Test
    fun `new unsupported schema is rejected`() {
        val state = manager.refresh(
            source(signed(BuiltInComponentCatalog.catalog.copy(schemaVersion = 6, catalogVersion = 2))),
        )

        assertEquals(CatalogUpdateStatus.SCHEMA_UNSUPPORTED, state.status)
        assertEquals(1L, state.activeCatalog.catalogVersion)
    }

    @Test
    fun `catalog downgrade is blocked`() {
        assertEquals(
            CatalogUpdateStatus.REMOTE_VERIFIED,
            manager.refresh(source(signed(catalogVersion = 3))).status,
        )

        val state = manager.refresh(source(signed(catalogVersion = 2)))

        assertEquals(CatalogUpdateStatus.ROLLBACK_BLOCKED, state.status)
        assertEquals(3L, state.activeCatalog.catalogVersion)
    }

    @Test
    fun `unsafe new rules retain the last trusted catalog`() {
        assertEquals(
            CatalogUpdateStatus.REMOTE_VERIFIED,
            manager.refresh(source(signed(catalogVersion = 2))).status,
        )
        val unsafe = BuiltInComponentCatalog.catalog.copy(
            catalogVersion = 3,
            deviceRules = BuiltInComponentCatalog.catalog.deviceRules.map { rule ->
                if (rule.ruleId == "harmonyos-version-5-plus-block") {
                    rule.copy(installWorkflowAllowed = true)
                } else {
                    rule
                }
            },
        )

        val state = manager.refresh(source(signed(unsafe)))

        assertEquals(CatalogUpdateStatus.REMOTE_REJECTED, state.status)
        assertEquals(2L, state.activeCatalog.catalogVersion)
        assertTrue(state.detail.contains("HarmonyOS 5+"))
    }

    @Test
    fun `offline first run uses the built-in catalog`() {
        val state = manager.refresh(CatalogPackageSource { null })

        assertEquals(CatalogUpdateStatus.BUILT_IN, state.status)
        assertEquals(BuiltInComponentCatalog.catalog, state.activeCatalog)
    }

    @Test
    fun `HarmonyOS 5 plus legacy installation prohibition cannot be removed`() {
        val missingBlock = BuiltInComponentCatalog.catalog.copy(
            catalogVersion = 2,
            deviceRules = BuiltInComponentCatalog.catalog.deviceRules.filterNot {
                it.ruleId == "harmonyos-next-family-block"
            },
        )

        val state = manager.refresh(source(signed(missingBlock)))

        assertEquals(CatalogUpdateStatus.REMOTE_REJECTED, state.status)
        assertFalse(state.activeCatalog.deviceRules.any { it.installWorkflowAllowed && it.ruleId.contains("5") })
    }

    @Test
    fun `ordinary user feedback cannot create DEVICE_VERIFIED`() {
        val userPromotion = BuiltInComponentCatalog.catalog.copy(
            catalogVersion = 2,
            compatibilityRecords = BuiltInComponentCatalog.catalog.compatibilityRecords.mapIndexed { index, record ->
                if (index == 0) {
                    record.copy(
                        status = CompatibilityValidationStatus.DEVICE_VERIFIED,
                        authority = CompatibilityEvidenceAuthority.USER_FEEDBACK,
                    )
                } else {
                    record
                }
            },
        )

        val state = manager.refresh(source(signed(userPromotion)))

        assertEquals(CatalogUpdateStatus.REMOTE_REJECTED, state.status)
        assertTrue(state.detail.contains("trusted device-lab"))
    }

    @Test
    fun `higher min client version is rejected without replacing trusted catalog`() {
        val state = manager.refresh(
            source(
                signed(
                    BuiltInComponentCatalog.catalog.copy(
                        catalogVersion = 2,
                        minClientVersion = 2,
                    ),
                ),
            ),
        )

        assertEquals(CatalogUpdateStatus.SCHEMA_UNSUPPORTED, state.status)
        assertEquals(1L, state.activeCatalog.catalogVersion)
    }

    private fun manager(
        keyPair: KeyPair,
        snapshotStore: TrustedCatalogStore,
    ) = CatalogUpdateManager(
        builtInCatalog = BuiltInComponentCatalog.catalog,
        signatureVerifier = CatalogSignatureVerifier(keyPair.public.encoded),
        store = snapshotStore,
        clientVersion = BuiltInComponentCatalog.CLIENT_VERSION,
    )

    private fun signed(
        catalogVersion: Long,
        keyPair: KeyPair = signingKey,
    ) = signed(BuiltInComponentCatalog.catalog.copy(catalogVersion = catalogVersion), keyPair)

    private fun signed(
        catalog: ComponentCatalog,
        keyPair: KeyPair = signingKey,
    ): SignedCatalogPackage {
        val bytes = gson.toJson(catalog).toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(bytes)
            sign()
        }
        return SignedCatalogPackage(bytes, signature)
    }

    private fun source(catalogPackage: SignedCatalogPackage) =
        CatalogPackageSource { catalogPackage }

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private class MemoryCatalogStore : TrustedCatalogStore {
        private var snapshot: TrustedCatalogSnapshot? = null

        override fun load(): TrustedCatalogSnapshot? = snapshot

        override fun save(snapshot: TrustedCatalogSnapshot) {
            this.snapshot = snapshot
        }
    }
}
