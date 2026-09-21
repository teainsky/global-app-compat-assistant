package com.example.globalcompat.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceValidationPromotionPolicyTest {
    private val policy = DeviceValidationPromotionPolicy()

    @Test
    fun `device and system information attain DEVICE_DETECTED only`() {
        val report = evaluate(input(componentEvidence = emptyList(), functionalEvidence = null))

        assertEquals(DeviceValidationEvidenceLevel.DEVICE_DETECTED, report.attainedLevel)
    }

    @Test
    fun `matching component versions without functional validation attain metadata level`() {
        val report = evaluate(input(functionalEvidence = null))

        assertEquals(DeviceValidationEvidenceLevel.COMPONENT_METADATA_MATCHED, report.attainedLevel)
    }

    @Test
    fun `three successful functional confirmations attain functionally validated`() {
        val report = evaluate(input())

        assertEquals(DeviceValidationEvidenceLevel.FUNCTIONALLY_VALIDATED, report.attainedLevel)
    }

    @Test
    fun `functional success without host artifact audit cannot become device verified`() {
        val report = evaluate(input())

        assertNotEquals(DeviceValidationEvidenceLevel.DEVICE_VERIFIED, report.attainedLevel)
        assertTrue(
            DeviceValidationPromotionPolicy.MISSING_TRUSTED_ARTIFACT_AUDIT in
                report.missingEvidence,
        )
    }

    @Test
    fun `artifact mismatch blocks promotion`() {
        val report = evaluate(
            input(artifactEvidence = artifactEvidence(match = false)),
        )

        assertEquals(DeviceValidationEvidenceLevel.FUNCTIONALLY_VALIDATED, report.attainedLevel)
        assertTrue(DeviceValidationPromotionPolicy.BLOCKER_ARTIFACT_MISMATCH in report.blockers)
    }

    @Test
    fun `trusted exact artifact pair plus functional success meets final threshold`() {
        val report = evaluate(
            input(artifactEvidence = artifactEvidence(match = true)),
        )

        assertEquals(DeviceValidationEvidenceLevel.DEVICE_VERIFIED, report.attainedLevel)
        assertTrue(report.missingEvidence.isEmpty())
        assertTrue(report.blockers.isEmpty())
    }

    @Test
    fun `HarmonyOS 4_2 evidence is not inherited by 4_3 or 5 plus`() {
        val differentSystemAudit = artifactEvidence(match = true).copy(
            systemProfile = SYSTEM_42.copy(
                harmonyOsVersion = "4.3",
                romVersion = "4.3",
            ),
        )
        val versionMismatch = evaluate(input(artifactEvidence = differentSystemAudit))
        assertNotEquals(DeviceValidationEvidenceLevel.DEVICE_VERIFIED, versionMismatch.attainedLevel)
        assertTrue(
            DeviceValidationPromotionPolicy.BLOCKER_ARTIFACT_PROFILE_MISMATCH in
                versionMismatch.blockers,
        )

        val harmony5 = evaluate(
            input(
                systemProfile = SYSTEM_42.copy(
                    harmonyOsVersion = "5.0",
                    romVersion = "5.0",
                    romFamily = "HARMONY_OS_5_PLUS",
                ),
                artifactEvidence = artifactEvidence(match = true).copy(
                    systemProfile = SYSTEM_42.copy(
                        harmonyOsVersion = "5.0",
                        romVersion = "5.0",
                        romFamily = "HARMONY_OS_5_PLUS",
                    ),
                ),
            ),
        )
        assertNotEquals(DeviceValidationEvidenceLevel.DEVICE_VERIFIED, harmony5.attainedLevel)
        assertTrue(DeviceValidationPromotionPolicy.BLOCKER_HARMONY_OS_5_PLUS in harmony5.blockers)
    }

    @Test
    fun `user supplied artifact fields cannot bypass trusted source requirement`() {
        val forged = artifactEvidence(match = true).copy(
            source = ValidationEvidenceSource.USER_SUPPLIED,
        )

        val report = evaluate(input(artifactEvidence = forged))

        assertEquals(DeviceValidationEvidenceLevel.FUNCTIONALLY_VALIDATED, report.attainedLevel)
        assertTrue(
            DeviceValidationPromotionPolicy.MISSING_TRUSTED_ARTIFACT_AUDIT in
                report.missingEvidence,
        )
    }

    @Test
    fun `remote user feedback cannot create functional or device verification`() {
        val remoteFeedback = successfulFunctional().copy(
            source = ValidationEvidenceSource.REMOTE_USER_FEEDBACK,
        )

        val report = evaluate(
            input(
                functionalEvidence = remoteFeedback,
                artifactEvidence = artifactEvidence(match = true),
            ),
        )

        assertEquals(DeviceValidationEvidenceLevel.COMPONENT_METADATA_MATCHED, report.attainedLevel)
    }

    @Test
    fun `blocked rule prevents final promotion after artifact verification`() {
        val report = evaluate(
            input(
                artifactEvidence = artifactEvidence(match = true),
                blockedRules = listOf("BLOCKED_VERSION:test"),
            ),
        )

        assertEquals(DeviceValidationEvidenceLevel.ARTIFACT_VERIFIED, report.attainedLevel)
        assertTrue("BLOCKED_VERSION:test" in report.blockers)
    }

    private fun evaluate(input: DeviceValidationEvidenceInput) =
        policy.evaluate(input, EVALUATED_AT)

    private fun input(
        deviceProfile: ValidationDeviceProfile = DEVICE,
        systemProfile: ValidationSystemProfile = SYSTEM_42,
        componentEvidence: List<ValidationComponentEvidence> = matchedComponents(),
        functionalEvidence: ValidationFunctionalEvidence? = successfulFunctional(),
        artifactEvidence: ValidationArtifactEvidence? = null,
        blockedRules: List<String> = emptyList(),
    ) = DeviceValidationEvidenceInput(
        deviceProfile = deviceProfile,
        systemProfile = systemProfile,
        componentEvidence = componentEvidence,
        functionalEvidence = functionalEvidence,
        artifactEvidence = artifactEvidence,
        blockedRules = blockedRules,
    )

    private fun matchedComponents() = PACKAGES.map { packageName ->
        ValidationComponentEvidence(
            packageName = packageName,
            versionCode = "1",
            versionName = "verified",
            metadataMatched = true,
            source = ValidationEvidenceSource.TRUSTED_CATALOG_COMPARISON,
        )
    }

    private fun successfulFunctional() = ValidationFunctionalEvidence(
        googleAccountLogin = true,
        chatGptLoginAndUse = true,
        chromeGoogleLogin = true,
        source = ValidationEvidenceSource.USER_CONFIRMATION,
    )

    private fun artifactEvidence(match: Boolean) = ValidationArtifactEvidence(
        deviceProfile = DEVICE,
        systemProfile = SYSTEM_42,
        components = PACKAGES.map { packageName ->
            ValidationArtifactComponentEvidence(
                packageName = packageName,
                sha256 = "a".repeat(64),
                signingCertificateSha256 = listOf("b".repeat(64)),
                officialArtifactMatched = match,
            )
        },
        source = ValidationEvidenceSource.TRUSTED_HOST_AUDIT,
    )

    private companion object {
        const val EVALUATED_AT = "2026-09-21T12:00:00Z"
        val PACKAGES = listOf("com.google.android.gms", "com.android.vending")
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
