package com.example.globalcompat.baseline

import com.example.globalcompat.catalog.ArtifactIntegrityStatus
import com.example.globalcompat.catalog.ComponentArtifact
import com.example.globalcompat.catalog.ComponentCatalog

class OfficialComponentMatcher(
    private val catalog: ComponentCatalog,
) {
    fun compare(
        fingerprints: List<InstalledComponentFingerprint>,
    ): List<OfficialComponentComparison> = fingerprints.map(::compareOne)

    private fun compareOne(fingerprint: InstalledComponentFingerprint): OfficialComponentComparison {
        val candidates = catalog.releases.flatMap { it.artifacts }
            .filter { it.packageName == fingerprint.packageName }
        val artifact = candidates.singleOrNull()
            ?: return comparison(fingerprint, null, OfficialComponentMatchStatus.UNKNOWN)
        val expectedVersionCode = artifact.artifactVersionCode?.toLongOrNull()
        val expectedVersionName = artifact.artifactVersionName
        val expectedSigner = artifact.signingCertificateDigest?.normalizeDigest()
        if (artifact.integrityStatus != ArtifactIntegrityStatus.SIGNATURE_VERIFIED ||
            artifact.sha256.isNullOrBlank() ||
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
        if (fingerprint.versionCode == null ||
            fingerprint.versionName.isNullOrBlank() ||
            fingerprint.signingCertificateSha256.isEmpty()
        ) {
            return comparison(fingerprint, artifact, OfficialComponentMatchStatus.UNREADABLE)
        }
        if (fingerprint.versionCode != expectedVersionCode ||
            fingerprint.versionName != expectedVersionName
        ) {
            return comparison(fingerprint, artifact, OfficialComponentMatchStatus.VERSION_MISMATCH)
        }
        val installedSigners = fingerprint.signingCertificateSha256
            .map { it.normalizeDigest() }
            .toSet()
        if (installedSigners != setOf(expectedSigner)) {
            return comparison(fingerprint, artifact, OfficialComponentMatchStatus.SIGNER_MISMATCH)
        }
        return comparison(fingerprint, artifact, OfficialComponentMatchStatus.OFFICIAL_METADATA_MATCH)
    }

    private fun comparison(
        fingerprint: InstalledComponentFingerprint,
        artifact: ComponentArtifact?,
        status: OfficialComponentMatchStatus,
    ) = OfficialComponentComparison(
        componentId = artifact?.componentId,
        fingerprint = fingerprint,
        status = status,
        expectedAssetId = artifact?.githubAssetId,
        expectedVersionCode = artifact?.artifactVersionCode?.toLongOrNull(),
        expectedVersionName = artifact?.artifactVersionName,
        expectedSignerSha256 = artifact?.signingCertificateDigest?.normalizeDigest(),
    )

    private fun String.normalizeDigest(): String = replace(":", "").lowercase()
}
