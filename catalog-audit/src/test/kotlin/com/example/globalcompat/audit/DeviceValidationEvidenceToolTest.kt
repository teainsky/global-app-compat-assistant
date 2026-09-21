package com.example.globalcompat.audit

import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.validation.DeviceValidationEvidenceLevel
import com.example.globalcompat.validation.DeviceValidationPromotionPolicy
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

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

    private fun systemProfileMap() = mapOf(
        "harmonyOsVersion" to "4.2",
        "androidVersion" to "12",
        "androidApiLevel" to 31,
        "romFamily" to "HARMONY_OS",
        "romVersion" to "4.2",
    )

    private fun officialArtifacts() = BuiltInComponentCatalog.catalog.releases
        .flatMap { it.artifacts }
        .sortedBy { it.packageName }

    private fun writeJson(name: String, value: Any): java.nio.file.Path {
        val path = temporaryFolder.root.toPath().resolve(name)
        Files.writeString(path, gson.toJson(value))
        return path
    }

    private companion object {
        const val DEVICE_MODEL = "Huawei Pura 70 Pro+"
        const val EVALUATED_AT = "2026-09-21T12:00:00Z"
        const val GOOGLE_REPORTED_SIGNER =
            "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
    }
}
