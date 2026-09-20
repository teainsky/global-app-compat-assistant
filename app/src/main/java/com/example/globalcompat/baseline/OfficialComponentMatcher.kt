package com.example.globalcompat.baseline

import com.example.globalcompat.catalog.ArtifactIntegrityStatus
import com.example.globalcompat.catalog.ComponentArtifact
import com.example.globalcompat.catalog.ComponentCatalog
import com.example.globalcompat.catalog.ComponentVariant
import com.example.globalcompat.catalog.InstalledArtifactSignatureStatus
import com.example.globalcompat.data.RomFamily

data class ComponentMatchContext(
    val manufacturer: String,
    val romFamily: RomFamily,
    val romVersion: String?,
)

class OfficialComponentMatcher(
    private val catalog: ComponentCatalog,
) {
    fun compare(
        fingerprints: List<InstalledComponentFingerprint>,
        context: ComponentMatchContext? = null,
        actualArtifactSha256ByPackage: Map<String, String> = emptyMap(),
    ): List<OfficialComponentComparison> = fingerprints.map { fingerprint ->
        compareOne(
            fingerprint = fingerprint,
            context = context,
            actualArtifactSha256 = actualArtifactSha256ByPackage[fingerprint.packageName],
        )
    }

    private fun compareOne(
        fingerprint: InstalledComponentFingerprint,
        context: ComponentMatchContext?,
        actualArtifactSha256: String?,
    ): OfficialComponentComparison {
        val candidates = catalog.releases.flatMap { it.artifacts }
            .filter { it.packageName == fingerprint.packageName }
        val artifact = candidates.singleOrNull()
            ?: return comparison(fingerprint, null, OfficialComponentMatchStatus.UNKNOWN)
        val expectedVersionCode = artifact.artifactVersionCode?.toLongOrNull()
        val expectedVersionName = artifact.artifactVersionName
        val expectedSigner = artifact.signingCertificateDigest?.normalizeDigest()
        val expectedSha256 = artifact.sha256?.normalizeDigest()
        if (artifact.integrityStatus != ArtifactIntegrityStatus.SIGNATURE_VERIFIED ||
            expectedSha256.isNullOrBlank() ||
            expectedVersionCode == null ||
            expectedVersionName.isNullOrBlank() ||
            expectedSigner.isNullOrBlank() ||
            artifact.githubAssetId == null
        ) {
            return comparison(fingerprint, artifact, OfficialComponentMatchStatus.UNKNOWN)
        }
        if (fingerprint.readStatus == ComponentFingerprintReadStatus.UNREADABLE) {
            return comparison(fingerprint, artifact, OfficialComponentMatchStatus.UNREADABLE)
        }
        if (!fingerprint.installed ||
            fingerprint.readStatus == ComponentFingerprintReadStatus.NOT_INSTALLED
        ) {
            return comparison(fingerprint, artifact, OfficialComponentMatchStatus.NOT_INSTALLED)
        }
        if (fingerprint.packageName != artifact.packageName) {
            return comparison(fingerprint, artifact, OfficialComponentMatchStatus.UNKNOWN)
        }
        if (fingerprint.versionCode == null || fingerprint.versionName.isNullOrBlank()) {
            return comparison(fingerprint, artifact, OfficialComponentMatchStatus.UNREADABLE)
        }
        if (fingerprint.versionCode != expectedVersionCode ||
            fingerprint.versionName != expectedVersionName
        ) {
            return comparison(fingerprint, artifact, OfficialComponentMatchStatus.VERSION_MISMATCH)
        }

        val reportedSigners = fingerprint.reportedSigningCertificateSha256
            .map { it.normalizeDigest() }
            .toSet()
        val signatureStatus = when {
            actualArtifactSha256?.normalizeDigest() == expectedSha256 ->
                InstalledArtifactSignatureStatus.ACTUAL_ARTIFACT_MATCH
            isHuaweiHarmonyCompatibilitySignature(context, artifact, reportedSigners) ->
                InstalledArtifactSignatureStatus.COMPATIBILITY_SIGNATURE_REPORTED
            reportedSigners.isEmpty() || reportedSigners == setOf(expectedSigner) ->
                InstalledArtifactSignatureStatus.UNKNOWN
            else -> InstalledArtifactSignatureStatus.SIGNER_MISMATCH
        }
        return comparison(
            fingerprint = fingerprint,
            artifact = artifact,
            status = OfficialComponentMatchStatus.VERSION_MATCH,
            signatureStatus = signatureStatus,
        )
    }

    private fun isHuaweiHarmonyCompatibilitySignature(
        context: ComponentMatchContext?,
        artifact: ComponentArtifact,
        reportedSigners: Set<String>,
    ): Boolean {
        val harmonyMajor = context?.romVersion
            ?.substringBefore('.')
            ?.filter(Char::isDigit)
            ?.toIntOrNull()
        return context != null &&
            context.manufacturer.contains("huawei", ignoreCase = true) &&
            context.romFamily == RomFamily.HARMONY_OS &&
            harmonyMajor in 1..4 &&
            artifact.variant == ComponentVariant.HUAWEI_HW &&
            artifact.artifactFilename?.endsWith("-hw.apk") == true &&
            reportedSigners == setOf(GOOGLE_PRIVILEGED_SIGNER_SHA256)
    }

    private fun comparison(
        fingerprint: InstalledComponentFingerprint,
        artifact: ComponentArtifact?,
        status: OfficialComponentMatchStatus,
        signatureStatus: InstalledArtifactSignatureStatus = InstalledArtifactSignatureStatus.UNKNOWN,
    ) = OfficialComponentComparison(
        componentId = artifact?.componentId,
        fingerprint = fingerprint,
        status = status,
        signatureStatus = signatureStatus,
        expectedAssetId = artifact?.githubAssetId,
        expectedVersionCode = artifact?.artifactVersionCode?.toLongOrNull(),
        expectedVersionName = artifact?.artifactVersionName,
        expectedSignerSha256 = artifact?.signingCertificateDigest?.normalizeDigest(),
    )

    private fun String.normalizeDigest(): String = replace(":", "").lowercase()

    companion object {
        // Official microG KnownGooglePackages privileged signing certificate allowlist.
        const val GOOGLE_PRIVILEGED_SIGNER_SHA256 =
            "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
    }
}
