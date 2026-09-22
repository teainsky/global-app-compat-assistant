package com.example.globalcompat.audit

import com.example.globalcompat.catalog.CatalogDecodeResult
import com.example.globalcompat.catalog.CatalogSchemaValidator
import com.example.globalcompat.catalog.CatalogSignatureVerifier
import com.example.globalcompat.catalog.CatalogVerifiedDeviceCompatibilityRecord
import com.example.globalcompat.catalog.CompatibilityCatalogCodec
import com.example.globalcompat.catalog.CompatibilityValidationStatus
import com.example.globalcompat.catalog.ComponentCatalog
import com.example.globalcompat.catalog.CatalogWarning
import com.example.globalcompat.validation.DeviceRecordPublicationResult
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.OffsetDateTime
import java.util.Base64

data class SignedCatalogPublication(
    val catalogBytes: ByteArray,
    val signatureBytes: ByteArray,
    val catalog: ComponentCatalog,
)

class SignedCatalogPublishingPipeline {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun publish(
        evidenceBytes: ByteArray,
        expectedEvidenceSha256: String,
        existingCatalogBytes: ByteArray,
        privateKeyPkcs8: ByteArray,
        publicKeyX509: ByteArray,
        publishedAt: String,
    ): SignedCatalogPublication {
        OffsetDateTime.parse(publishedAt)
        val decoded = CompatibilityCatalogCodec().decode(existingCatalogBytes)
        val current = (decoded as? CatalogDecodeResult.Success)?.catalog
            ?: error("Existing compatibility catalog is invalid: $decoded")
        val publication = DeviceVerificationPublishingPipeline(current).publish(
            evidenceBytes = evidenceBytes,
            expectedEvidenceSha256 = expectedEvidenceSha256,
        )
        val approved = publication as? DeviceRecordPublicationResult.Approved
            ?: error(
                "Device verification publishing pipeline rejected the evidence: " +
                    (publication as DeviceRecordPublicationResult.Rejected).reasons.joinToString(),
            )
        val record = approved.record
        val catalogRecord = CatalogVerifiedDeviceCompatibilityRecord(
            schemaVersion = record.schemaVersion,
            deviceModel = record.deviceModel,
            deviceFamily = record.deviceFamily,
            romFamily = record.romFamily,
            harmonyOsVersion = record.harmonyOsVersion,
            androidApiLevel = record.androidApiLevel,
            componentRelease = record.componentRelease,
            componentVersionCodes = record.componentVersionCodes,
            componentSignerDigests = record.componentSignerDigests,
            validationDate = record.validationDate,
            evidenceDigest = record.evidenceDigest,
            compatibilityStatus = CompatibilityValidationStatus.DEVICE_VERIFIED,
        )
        val exactKey: (CatalogVerifiedDeviceCompatibilityRecord) -> List<String> = {
            listOf(
                it.deviceModel,
                it.deviceFamily,
                it.romFamily,
                it.harmonyOsVersion,
                it.androidApiLevel.toString(),
                it.componentRelease,
            )
        }
        val nextCatalog = current.copy(
            catalogVersion = current.catalogVersion + 1,
            publishedAt = publishedAt,
            verifiedDeviceRecords = (
                current.verifiedDeviceRecords.filter { exactKey(it) != exactKey(catalogRecord) } +
                    catalogRecord
                ).sortedBy { exactKey(it).joinToString("|") },
            warnings = current.warnings.filterNot {
                it.code == LEGACY_UNVERIFIED_WARNING
            } + CatalogWarning(
                code = EXACT_PROFILE_ONLY_WARNING,
                message =
                    "DEVICE_VERIFIED applies only to exact signed device records; no model or system inheritance is allowed.",
            ),
        )
        val validation = CatalogSchemaValidator(CLIENT_VERSION).validate(nextCatalog)
        check(validation.isValid) {
            "Published compatibility catalog is unsafe: ${validation.errors.joinToString()}"
        }
        val catalogBytes = (gson.toJson(nextCatalog) + "\n").encodeToByteArray()
        val privateKey = KeyFactory.getInstance(KEY_ALGORITHM)
            .generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))
        val signatureBytes = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(catalogBytes)
            sign()
        }
        check(CatalogSignatureVerifier(publicKeyX509).verify(catalogBytes, signatureBytes)) {
            "Generated catalog signature does not match the supplied public key"
        }
        return SignedCatalogPublication(catalogBytes, signatureBytes, nextCatalog)
    }

    companion object {
        const val CLIENT_VERSION = 1L
        private const val KEY_ALGORITHM = "EC"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val LEGACY_UNVERIFIED_WARNING = "COMPATIBILITY_NOT_DEVICE_VERIFIED"
        private const val EXACT_PROFILE_ONLY_WARNING = "DEVICE_VERIFICATION_EXACT_PROFILE_ONLY"
    }
}

object CatalogSigningKeyGenerator {
    fun generate(privateKeyPath: Path, publicKeyPath: Path) {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        privateKeyPath.parent?.let(Files::createDirectories)
        publicKeyPath.parent?.let(Files::createDirectories)
        Files.write(privateKeyPath, keyPair.private.encoded)
        Files.write(publicKeyPath, keyPair.public.encoded)
    }
}

object SignedCatalogWriter {
    fun write(publication: SignedCatalogPublication, catalogPath: Path, signaturePath: Path) {
        catalogPath.parent?.let(Files::createDirectories)
        signaturePath.parent?.let(Files::createDirectories)
        Files.write(catalogPath, publication.catalogBytes)
        Files.writeString(signaturePath, Base64.getEncoder().encodeToString(publication.signatureBytes) + "\n")
    }
}
