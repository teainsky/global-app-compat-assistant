package com.example.globalcompat.audit

import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.validation.DeviceValidationEvidenceLevel
import com.example.globalcompat.validation.DeviceValidationPromotionPolicy
import com.example.globalcompat.validation.ValidationEvidenceSource
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.security.MessageDigest

class DeviceValidationEvidenceToolTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val gson = Gson()
    private val tool = DeviceValidationEvidenceTool()

    @Test
    fun `Pura baseline with successful confirmations stops at functional validation`() {
        val baseline = writeJson("device-baseline.json", baselineDocument())

        val report = tool.evaluate(baseline, evaluatedAt = EVALUATED_AT)

        assertEquals(DeviceValidationEvidenceLevel.FUNCTIONALLY_VALIDATED, report.attainedLevel)
        assertTrue(
            DeviceValidationPromotionPolicy.MISSING_TRUSTED_ARTIFACT_AUDIT in
                report.missingEvidence,
        )
        assertTrue(report.missingEvidence.any { it.startsWith("OFFICIAL_ARTIFACT_MATCH_REQUIRED") })
    }

    @Test
    fun `future exact host audit can satisfy final promotion threshold`() {
        val baseline = writeJson("device-baseline.json", baselineDocument())
        val audit = writeJson("device-artifact-audit.json", artifactAuditDocument())

        val report = tool.evaluate(baseline, audit, EVALUATED_AT)

        assertEquals(DeviceValidationEvidenceLevel.DEVICE_VERIFIED, report.attainedLevel)
        assertTrue(report.blockers.isEmpty())
    }

    @Test
    fun `schema v3 embedded exact artifact audit can pass developer review`() {
        val baseline = writeJson("device-baseline-v3.json", baselineV3Document())

        val report = tool.evaluate(
            baseline,
            evaluatedAt = EVALUATED_AT,
            expectedBaselineSha256 = sha256(Files.readAllBytes(baseline)),
        )

        assertEquals(DeviceValidationEvidenceLevel.DEVICE_VERIFIED, report.attainedLevel)
        assertEquals("HBN-AL80", report.deviceProfile?.model)
        assertEquals("4.2.0", report.systemProfile?.harmonyOsVersion)
        assertEquals(31, report.systemProfile?.androidApiLevel)
        assertEquals(
            ValidationEvidenceSource.DEVELOPER_REVIEWED_ON_DEVICE_AUDIT,
            report.artifactEvidence?.source,
        )
        assertTrue(report.artifactEvidence?.components?.all { it.officialArtifactMatched } == true)
        assertTrue(report.missingEvidence.isEmpty())
        assertTrue(report.blockers.isEmpty())
    }

    @Test
    fun `schema v3 exact audit without reviewed digest stays artifact verified`() {
        val baseline = writeJson("device-baseline-v3-unreviewed.json", baselineV3Document())

        val report = tool.evaluate(baseline, evaluatedAt = EVALUATED_AT)

        assertEquals(DeviceValidationEvidenceLevel.ARTIFACT_VERIFIED, report.attainedLevel)
        assertTrue(
            DeviceValidationPromotionPolicy.MISSING_TRUSTED_ARTIFACT_AUDIT in
                report.missingEvidence,
        )
    }

    @Test
    fun `schema v3 reviewed embedded hash mismatch fails closed despite claimed match`() {
        val firstPackage = officialArtifacts().first().packageName
        val baseline = writeJson(
            "device-baseline-v3-mismatch.json",
            baselineV3Document(mapOf(firstPackage to "a".repeat(64))),
        )

        val report = tool.evaluate(
            baseline,
            evaluatedAt = EVALUATED_AT,
            expectedBaselineSha256 = sha256(Files.readAllBytes(baseline)),
        )

        assertNotEquals(DeviceValidationEvidenceLevel.DEVICE_VERIFIED, report.attainedLevel)
        assertTrue(DeviceValidationPromotionPolicy.BLOCKER_ARTIFACT_MISMATCH in report.blockers)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `schema v3 baseline modified after digest approval is rejected`() {
        val baseline = writeJson("device-baseline-v3-tampered.json", baselineV3Document())
        val reviewedDigest = sha256(Files.readAllBytes(baseline))
        Files.writeString(baseline, Files.readString(baseline) + " ")

        tool.evaluate(
            baseline,
            evaluatedAt = EVALUATED_AT,
            expectedBaselineSha256 = reviewedDigest,
        )
    }

    @Test
    fun `forged status and source fields cannot bypass independent fact comparison`() {
        val baseline = writeJson(
            "device-baseline.json",
            baselineDocument().toMutableMap().apply {
                put("attainedLevel", "DEVICE_VERIFIED")
                put("deviceValidationRecord", mapOf("status" to "DEVICE_VERIFIED"))
            },
        )
        val forgedAudit = artifactAuditDocument().toMutableMap().apply {
            put("source", "TRUSTED_HOST_AUDIT")
            val artifacts = officialArtifacts().mapIndexed { index, artifact ->
                artifactAuditItem(
                    artifact = artifact,
                    sha256 = if (index == 0) "a".repeat(64) else artifact.sha256,
                )
            }
            put("artifacts", artifacts)
        }
        val audit = writeJson("device-artifact-audit.json", forgedAudit)

        val report = tool.evaluate(baseline, audit, EVALUATED_AT)

        assertNotEquals(DeviceValidationEvidenceLevel.DEVICE_VERIFIED, report.attainedLevel)
        assertTrue(DeviceValidationPromotionPolicy.BLOCKER_ARTIFACT_MISMATCH in report.blockers)
    }

    @Test
    fun `writer emits required machine readable evidence fields`() {
        val report = tool.evaluate(
            writeJson("device-baseline.json", baselineDocument()),
            evaluatedAt = EVALUATED_AT,
        )
        val output = temporaryFolder.root.toPath().resolve("device-validation-evidence.json")

        DeviceValidationEvidenceWriter.write(report, output)

        val json = Files.readString(output)
        listOf(
            "deviceProfile",
            "systemProfile",
            "componentEvidence",
            "functionalEvidence",
            "artifactEvidence",
            "attainedLevel",
            "missingEvidence",
            "blockers",
            "evaluatedAt",
        ).forEach { field -> assertTrue("missing field: $field", json.contains("\"$field\"")) }
    }

    private fun baselineDocument(): Map<String, Any?> {
        val artifacts = officialArtifacts()
        return mapOf(
            "schemaVersion" to 2,
            "capturedAtEpochMillis" to 1_789_896_000_000L,
            "device" to mapOf("model" to DEVICE_MODEL),
            "system" to systemProfileMap(),
            "components" to artifacts.map { artifact ->
                mapOf(
                    "installed" to true,
                    "enabled" to true,
                    "packageName" to artifact.packageName,
                    "versionCode" to artifact.artifactVersionCode?.toLong(),
                    "versionName" to artifact.artifactVersionName,
                    "reportedSigningCertificateSha256" to listOf(GOOGLE_REPORTED_SIGNER),
                    "installSource" to "com.huawei.appmarket",
                    "officialMatchStatus" to "VERSION_MATCH",
                    "signatureStatus" to "COMPATIBILITY_SIGNATURE_REPORTED",
                )
            },
            "functionalValidation" to mapOf(
                "googleAccountLogin" to "YES",
                "chatGptLoginAndUse" to "YES",
                "chromeGoogleLogin" to "YES",
            ),
        )
    }

    private fun artifactAuditDocument(): Map<String, Any?> = mapOf(
        "schemaVersion" to 1,
        "status" to "PASS",
        "auditedAt" to EVALUATED_AT,
        "deviceProfile" to mapOf("manufacturer" to null, "model" to DEVICE_MODEL),
        "systemProfile" to systemProfileMap(),
        "artifacts" to officialArtifacts().map(::artifactAuditItem),
    )

    private fun baselineV3Document(
        installedShaOverrides: Map<String, String> = emptyMap(),
    ): Map<String, Any?> = baselineDocument().toMutableMap().apply {
        put("schemaVersion", 3)
        put("attainedEvidenceLevel", "ARTIFACT_VERIFIED")
        put("device", mapOf("model" to "HBN-AL80"))
        put("system", systemProfileMap("4.2.0"))
        put(
            "components",
            officialArtifacts().map { artifact ->
                mapOf(
                    "installed" to true,
                    "enabled" to true,
                    "packageName" to artifact.packageName,
                    "versionCode" to artifact.artifactVersionCode?.toLong(),
                    "versionName" to artifact.artifactVersionName,
                    "reportedSigningCertificateSha256" to listOf(GOOGLE_REPORTED_SIGNER),
                    "installSource" to "com.android.packageinstaller",
                    "officialMatchStatus" to "VERSION_MATCH",
                    "signatureStatus" to "COMPATIBILITY_SIGNATURE_REPORTED",
                    "artifactAudit" to mapOf(
                        "readStatus" to "AUDITED",
                        "installedApkSha256" to
                            installedShaOverrides.getOrDefault(
                                artifact.packageName,
                                artifact.sha256.orEmpty(),
                            ),
                        "officialApkSha256" to artifact.sha256,
                        "artifactMatchStatus" to "ACTUAL_ARTIFACT_MATCH",
                    ),
                )
            },
        )
    }

    private fun artifactAuditItem(
        artifact: com.example.globalcompat.catalog.ComponentArtifact,
        sha256: String? = artifact.sha256,
    ) = mapOf(
        "componentId" to artifact.componentId,
        "packageName" to artifact.packageName,
        "versionCode" to artifact.artifactVersionCode,
        "versionName" to artifact.artifactVersionName,
        "locallyCalculatedSha256" to sha256,
        "signingCertificateSha256" to listOf(artifact.signingCertificateDigest),
        "apksignerVerificationResult" to "PASS",
        "signatureStatus" to "ACTUAL_ARTIFACT_MATCH",
        "failure" to null,
    )

    private fun systemProfileMap(version: String = "4.2") = mapOf(
        "harmonyOsVersion" to version,
        "androidVersion" to "12",
        "androidApiLevel" to 31,
        "romFamily" to "HARMONY_OS",
        "romVersion" to version,
    )

    private fun officialArtifacts() = BuiltInComponentCatalog.catalog.releases
        .flatMap { it.artifacts }
        .sortedBy { it.packageName }

    private fun writeJson(name: String, value: Any): java.nio.file.Path {
        val path = temporaryFolder.root.toPath().resolve(name)
        Files.writeString(path, gson.toJson(value))
        return path
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val DEVICE_MODEL = "Huawei Pura 70 Pro+"
        const val EVALUATED_AT = "2026-09-21T12:00:00Z"
        const val GOOGLE_REPORTED_SIGNER =
            "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
    }
}
