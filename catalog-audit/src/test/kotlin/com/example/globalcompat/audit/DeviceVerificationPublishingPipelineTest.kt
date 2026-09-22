package com.example.globalcompat.audit

import com.example.globalcompat.catalog.BlockedVersionRule
import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.CatalogSignatureVerifier
import com.example.globalcompat.catalog.CompatibilityValidationStatus
import com.example.globalcompat.validation.DeviceRecordPublicationRejection
import com.example.globalcompat.validation.DeviceRecordPublicationResult
import com.example.globalcompat.validation.DeviceValidationEvidenceInput
import com.example.globalcompat.validation.DeviceValidationEvidenceLevel
import com.example.globalcompat.validation.DeviceValidationPromotionPolicy
import com.example.globalcompat.validation.PublishedCompatibilityStatus
import com.example.globalcompat.validation.ValidationArtifactComponentEvidence
import com.example.globalcompat.validation.ValidationArtifactEvidence
import com.example.globalcompat.validation.ValidationComponentEvidence
import com.example.globalcompat.validation.ValidationDeviceProfile
import com.example.globalcompat.validation.ValidationEvidenceSource
import com.example.globalcompat.validation.ValidationFunctionalEvidence
import com.example.globalcompat.validation.ValidationSystemProfile
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class DeviceVerificationPublishingPipelineTest {
    private val gson = Gson()
    private val policy = DeviceValidationPromotionPolicy()
    private val catalog = BuiltInComponentCatalog.catalog
    private val artifacts = catalog.releases.single().artifacts

    @Test
    fun `complete DEVICE_VERIFIED evidence generates exact formal record`() {
        val evidence = evidenceBytes(fullReport())

        val result = DeviceVerificationPublishingPipeline(catalog).publish(
            evidence,
            sha256(evidence),
        ) as DeviceRecordPublicationResult.Approved

        assertEquals(DEVICE.model, result.record.deviceModel)
        assertEquals(DEVICE.model, result.record.deviceFamily)
        assertEquals("4.2", result.record.harmonyOsVersion)
        assertEquals("v0.3.16.252432", result.record.componentRelease)
        assertEquals(PublishedCompatibilityStatus.DEVICE_VERIFIED, result.record.compatibilityStatus)
        assertEquals(sha256(evidence), result.record.evidenceDigest)
        assertEquals(
            artifacts.associate { it.packageName to it.artifactVersionCode },
            result.record.componentVersionCodes,
        )
    }

    @Test
    fun `approved evidence is added to a newly signed exact-profile catalog`() {
        val evidence = evidenceBytes(fullReport())
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        val publication = SignedCatalogPublishingPipeline().publish(
            evidenceBytes = evidence,
            expectedEvidenceSha256 = sha256(evidence),
            existingCatalogBytes = checkNotNull(
                javaClass.getResourceAsStream("/compatibility-catalog.json"),
            ).use { it.readBytes() },
            privateKeyPkcs8 = keyPair.private.encoded,
            publicKeyX509 = keyPair.public.encoded,
            publishedAt = "2026-09-22T12:00:00+08:00",
        )

        assertTrue(
            CatalogSignatureVerifier(keyPair.public.encoded).verify(
                publication.catalogBytes,
                publication.signatureBytes,
            ),
        )
        val record = publication.catalog.verifiedDeviceRecords.single {
            it.deviceModel == DEVICE.model
        }
        assertEquals(DEVICE.model, record.deviceModel)
        assertEquals("4.2", record.harmonyOsVersion)
        assertEquals(31, record.androidApiLevel)
        assertEquals(CompatibilityValidationStatus.DEVICE_VERIFIED, record.compatibilityStatus)
        assertEquals(sha256(evidence), record.evidenceDigest)
        assertEquals(catalog.catalogVersion + 1, publication.catalog.catalogVersion)
    }

    @Test
    fun `developer reviewed on device evidence generates exact formal record`() {
        val report = policy.evaluate(
            baseInput(
                artifactEvidence = artifactEvidence(SYSTEM_42).copy(
                    source = ValidationEvidenceSource.DEVELOPER_REVIEWED_ON_DEVICE_AUDIT,
                ),
            ),
            EVALUATED_AT,
        )
        val evidence = evidenceBytes(report)

        val result = DeviceVerificationPublishingPipeline(catalog).publish(
            evidence,
            sha256(evidence),
        ) as DeviceRecordPublicationResult.Approved

        assertEquals(DEVICE.model, result.record.deviceModel)
        assertEquals(PublishedCompatibilityStatus.DEVICE_VERIFIED, result.record.compatibilityStatus)
    }

    @Test
    fun `current Pura functional baseline is explicitly rejected`() {
        val report = policy.evaluate(
            baseInput(artifactEvidence = null),
            EVALUATED_AT,
        )
        assertEquals(DeviceValidationEvidenceLevel.FUNCTIONALLY_VALIDATED, report.attainedLevel)
        val evidence = evidenceBytes(report)

        val result = rejected(evidence, sha256(evidence))

        assertTrue(
            DeviceRecordPublicationRejection.EVIDENCE_LEVEL_NOT_DEVICE_VERIFIED in result.reasons,
        )
    }

    @Test
    fun `one byte evidence modification is rejected by digest`() {
        val reviewed = evidenceBytes(fullReport())
        val tampered = reviewed + byteArrayOf(' '.code.toByte())

        val result = rejected(tampered, sha256(reviewed))

        assertEquals(
            listOf(DeviceRecordPublicationRejection.EVIDENCE_DIGEST_MISMATCH),
            result.reasons,
        )
    }

    @Test
    fun `HarmonyOS 4_2 evidence cannot be retargeted to 4_3`() {
        val changedSystem = SYSTEM_42.copy(harmonyOsVersion = "4.3", romVersion = "4.3")
        val forged = fullReport().copy(systemProfile = changedSystem)
        val evidence = evidenceBytes(forged)

        val result = rejected(evidence, sha256(evidence))

        assertTrue(DeviceRecordPublicationRejection.EXACT_PROFILE_MISMATCH in result.reasons)
    }

    @Test
    fun `exact device evidence cannot be retargeted to another Pura model`() {
        val forged = fullReport().copy(
            deviceProfile = DEVICE.copy(model = "Another Pura Model"),
        )
        val evidence = evidenceBytes(forged)

        val result = rejected(evidence, sha256(evidence))

        assertTrue(DeviceRecordPublicationRejection.EXACT_PROFILE_MISMATCH in result.reasons)
    }

    @Test
    fun `blocked component version is rejected`() {
        val blockedArtifact = artifacts.first()
        val blockedCatalog = catalog.copy(
            blockedVersions = listOf(
                BlockedVersionRule(
                    componentId = blockedArtifact.componentId,
                    versions = setOf(blockedArtifact.artifactVersionCode.orEmpty()),
                    reason = "test block",
                ),
            ),
        )
        val evidence = evidenceBytes(fullReport())

        val result = rejected(evidence, sha256(evidence), blockedCatalog)

        assertTrue(DeviceRecordPublicationRejection.BLOCKED_VERSION in result.reasons)
    }

    @Test
    fun `HarmonyOS 5 plus cannot publish legacy Huawei microG record`() {
        val system5 = SYSTEM_42.copy(
            harmonyOsVersion = "5.0",
            romFamily = "HARMONY_OS_5_PLUS",
            romVersion = "5.0",
        )
        val forged = fullReport().copy(
            systemProfile = system5,
            artifactEvidence = artifactEvidence(system5),
            attainedLevel = DeviceValidationEvidenceLevel.DEVICE_VERIFIED,
            missingEvidence = emptyList(),
            blockers = emptyList(),
        )
        val evidence = evidenceBytes(forged)

        val result = rejected(evidence, sha256(evidence))

        assertTrue(
            DeviceRecordPublicationRejection.HARMONYOS_5_PLUS_NOT_PUBLISHABLE in result.reasons,
        )
    }

    @Test
    fun `artifact mismatch is rejected even when level field is forged`() {
        val mismatchedArtifacts = artifactEvidence(SYSTEM_42).copy(
            components = artifactEvidence(SYSTEM_42).components.mapIndexed { index, component ->
                if (index == 0) component.copy(officialArtifactMatched = false) else component
            },
        )
        val forged = fullReport().copy(
            artifactEvidence = mismatchedArtifacts,
            attainedLevel = DeviceValidationEvidenceLevel.DEVICE_VERIFIED,
        )
        val evidence = evidenceBytes(forged)

        val result = rejected(evidence, sha256(evidence))

        assertTrue(DeviceRecordPublicationRejection.ARTIFACT_MISMATCH in result.reasons)
    }

    private fun fullReport() = policy.evaluate(
        baseInput(artifactEvidence = artifactEvidence(SYSTEM_42)),
        EVALUATED_AT,
    )

    private fun baseInput(
        artifactEvidence: ValidationArtifactEvidence?,
    ) = DeviceValidationEvidenceInput(
        deviceProfile = DEVICE,
        systemProfile = SYSTEM_42,
        componentEvidence = artifacts.map { artifact ->
            ValidationComponentEvidence(
                packageName = artifact.packageName,
                versionCode = artifact.artifactVersionCode,
                versionName = artifact.artifactVersionName,
                metadataMatched = true,
                source = ValidationEvidenceSource.TRUSTED_CATALOG_COMPARISON,
            )
        },
        functionalEvidence = ValidationFunctionalEvidence(
            googleAccountLogin = true,
            chatGptLoginAndUse = true,
            chromeGoogleLogin = true,
            source = ValidationEvidenceSource.USER_CONFIRMATION,
        ),
        artifactEvidence = artifactEvidence,
    )

    private fun artifactEvidence(system: ValidationSystemProfile) = ValidationArtifactEvidence(
        deviceProfile = DEVICE,
        systemProfile = system,
        components = artifacts.map { artifact ->
            ValidationArtifactComponentEvidence(
                packageName = artifact.packageName,
                sha256 = artifact.sha256,
                signingCertificateSha256 = listOf(artifact.signingCertificateDigest.orEmpty()),
                officialArtifactMatched = true,
            )
        },
        source = ValidationEvidenceSource.TRUSTED_HOST_AUDIT,
    )

    private fun rejected(
        evidence: ByteArray,
        expectedDigest: String,
        componentCatalog: com.example.globalcompat.catalog.ComponentCatalog = catalog,
    ) = DeviceVerificationPublishingPipeline(componentCatalog).publish(
        evidence,
        expectedDigest,
    ) as DeviceRecordPublicationResult.Rejected

    private fun evidenceBytes(value: Any): ByteArray = gson.toJson(value).toByteArray()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val EVALUATED_AT = "2026-09-21T12:00:00Z"
        val DEVICE = ValidationDeviceProfile("HUAWEI", "Huawei Pura 70 Pro+")
        val SYSTEM_42 = ValidationSystemProfile(
            harmonyOsVersion = "4.2",
            androidVersion = "12",
            androidApiLevel = 31,
            romFamily = "HARMONY_OS",
            romVersion = "4.2",
        )
    }
}
