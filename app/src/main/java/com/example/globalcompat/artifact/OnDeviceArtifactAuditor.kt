package com.example.globalcompat.artifact

import com.example.globalcompat.baseline.OfficialComponentMatcher
import com.example.globalcompat.catalog.ArtifactIntegrityStatus
import com.example.globalcompat.catalog.CatalogSnapshot
import com.example.globalcompat.catalog.ComponentArtifact
import com.example.globalcompat.validation.DeviceValidationEvidenceLevel
import com.example.globalcompat.validation.ValidationArtifactComponentEvidence
import com.example.globalcompat.validation.ValidationArtifactEvidence
import com.example.globalcompat.validation.ValidationEvidenceSource

class OnDeviceArtifactAuditor(
    private val catalogSnapshot: CatalogSnapshot?,
    private val installedApkLookup: InstalledApkPathLookup,
    private val digestReader: ApkByteDigestReader,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun audit(request: OnDeviceArtifactAuditRequest): OnDeviceArtifactAuditReport {
        val components = TARGET_PACKAGES.map { packageName ->
            auditComponent(packageName, request)
        }
        val readableCount = components.count {
            it.installedApkSha256 != null &&
                it.readStatus != OnDeviceArtifactReadStatus.APK_FILE_NOT_READABLE
        }
        val availability = when {
            readableCount == TARGET_PACKAGES.size && components.all {
                it.readStatus == OnDeviceArtifactReadStatus.AUDITED
            } -> OnDeviceArtifactAuditAvailability.ON_DEVICE_ARTIFACT_AUDIT_SUPPORTED
            readableCount > 0 -> OnDeviceArtifactAuditAvailability.PARTIALLY_SUPPORTED
            else -> OnDeviceArtifactAuditAvailability.NOT_ACCESSIBLE
        }
        val evidence = ValidationArtifactEvidence(
            deviceProfile = request.deviceProfile,
            systemProfile = request.systemProfile,
            components = components.map { component ->
                ValidationArtifactComponentEvidence(
                    packageName = component.packageName,
                    sha256 = component.installedApkSha256,
                    signingCertificateSha256 =
                        component.reportedSigningCertificateSha256,
                    officialArtifactMatched = component.officialArtifactMatched,
                )
            },
            source = ValidationEvidenceSource.ON_DEVICE_READ_ONLY_AUDIT,
        )
        return OnDeviceArtifactAuditReport(
            schemaVersion = SCHEMA_VERSION,
            availability = availability,
            components = components,
            artifactEvidence = evidence,
            attainedEvidenceLevel = if (components.all { it.officialArtifactMatched }) {
                DeviceValidationEvidenceLevel.ARTIFACT_VERIFIED
            } else {
                null
            },
            auditedAtEpochMillis = clock(),
            catalogVersion = catalogSnapshot?.catalogVersion,
            catalogDigest = catalogSnapshot?.catalogDigest,
        )
    }

    private fun auditComponent(
        packageName: String,
        request: OnDeviceArtifactAuditRequest,
    ): OnDeviceArtifactComponentResult {
        val artifact = catalogSnapshot?.catalog?.releases.orEmpty().flatMap { it.artifacts }
            .filter { it.packageName == packageName }
            .singleOrNull()
            ?.takeIf { it.hasAuditedMetadata() }
            ?: return unavailable(
                packageName,
                OnDeviceArtifactReadStatus.OFFICIAL_METADATA_UNAVAILABLE,
            )
        val lookup = runCatching { installedApkLookup.read(packageName) }.getOrElse {
            return unavailable(
                packageName,
                OnDeviceArtifactReadStatus.PACKAGE_METADATA_UNREADABLE,
                it::class.java.simpleName,
                artifact.sha256,
            )
        }
        if (lookup.status == InstalledApkLookupStatus.NOT_INSTALLED) {
            return unavailable(
                packageName,
                OnDeviceArtifactReadStatus.NOT_INSTALLED,
                officialSha256 = artifact.sha256,
            )
        }
        val descriptor = lookup.descriptor
            ?: return unavailable(
                packageName,
                OnDeviceArtifactReadStatus.PACKAGE_METADATA_UNREADABLE,
                officialSha256 = artifact.sha256,
            )
        if (descriptor.baseApkPath.isBlank()) {
            return unavailable(
                packageName,
                OnDeviceArtifactReadStatus.SOURCE_PATH_UNAVAILABLE,
                officialSha256 = artifact.sha256,
                descriptor = descriptor,
            )
        }
        val digest = runCatching { digestReader.readSha256(descriptor.baseApkPath) }
            .getOrElse {
                ApkByteDigestResult(false, null, it::class.java.simpleName)
            }
        if (!digest.readable || digest.sha256.isNullOrBlank()) {
            return unavailable(
                packageName,
                OnDeviceArtifactReadStatus.APK_FILE_NOT_READABLE,
                digest.failureType,
                artifact.sha256,
                descriptor,
            )
        }

        val packageMatched = descriptor.packageName == artifact.packageName
        val versionMatched = descriptor.versionCode == artifact.artifactVersionCode &&
            descriptor.versionName == artifact.artifactVersionName
        val signerAccepted = signerAccepted(descriptor, artifact, request)
        val hashMatched = digest.sha256.normalizeDigest() == artifact.sha256?.normalizeDigest()
        val hasSplits = descriptor.splitApkPaths.isNotEmpty()
        val officialMatched = !hasSplits && hashMatched && packageMatched &&
            versionMatched && signerAccepted
        return OnDeviceArtifactComponentResult(
            packageName = packageName,
            readStatus = if (hasSplits) {
                OnDeviceArtifactReadStatus.SPLIT_APK_LAYOUT_UNSUPPORTED
            } else {
                OnDeviceArtifactReadStatus.AUDITED
            },
            versionCode = descriptor.versionCode,
            versionName = descriptor.versionName,
            reportedSigningCertificateSha256 =
                descriptor.reportedSigningCertificateSha256,
            splitApkCount = descriptor.splitApkPaths.size,
            installedApkSha256 = digest.sha256.normalizeDigest(),
            officialApkSha256 = artifact.sha256?.normalizeDigest(),
            packageMatched = packageMatched,
            versionMatched = versionMatched,
            signerAccepted = signerAccepted,
            officialArtifactMatched = officialMatched,
            failureType = null,
        )
    }

    private fun signerAccepted(
        descriptor: InstalledApkDescriptor,
        artifact: ComponentArtifact,
        request: OnDeviceArtifactAuditRequest,
    ): Boolean {
        val reported = descriptor.reportedSigningCertificateSha256
            .map { it.normalizeDigest() }
            .toSet()
        val artifactSigner = artifact.signingCertificateDigest?.normalizeDigest()
        val harmonyMajor = request.systemProfile.harmonyOsVersion
            ?.let { VERSION_NUMBER.find(it)?.value?.toIntOrNull() }
            ?: request.systemProfile.romVersion
                ?.let { VERSION_NUMBER.find(it)?.value?.toIntOrNull() }
        val compatibilitySignature =
            request.deviceProfile.manufacturer?.contains("huawei", ignoreCase = true) == true &&
                request.systemProfile.romFamily == "HARMONY_OS" &&
                harmonyMajor in 1..4 &&
                reported == setOf(OfficialComponentMatcher.GOOGLE_PRIVILEGED_SIGNER_SHA256)
        return reported.isNotEmpty() &&
            (reported == setOf(artifactSigner) || compatibilitySignature)
    }

    private fun unavailable(
        packageName: String,
        status: OnDeviceArtifactReadStatus,
        failureType: String? = null,
        officialSha256: String? = null,
        descriptor: InstalledApkDescriptor? = null,
    ) = OnDeviceArtifactComponentResult(
        packageName = packageName,
        readStatus = status,
        versionCode = descriptor?.versionCode,
        versionName = descriptor?.versionName,
        reportedSigningCertificateSha256 =
            descriptor?.reportedSigningCertificateSha256.orEmpty(),
        splitApkCount = descriptor?.splitApkPaths?.size ?: 0,
        installedApkSha256 = null,
        officialApkSha256 = officialSha256?.normalizeDigest(),
        packageMatched = false,
        versionMatched = false,
        signerAccepted = false,
        officialArtifactMatched = false,
        failureType = failureType,
    )

    private fun ComponentArtifact.hasAuditedMetadata(): Boolean =
        integrityStatus == ArtifactIntegrityStatus.SIGNATURE_VERIFIED &&
            !sha256.isNullOrBlank() &&
            !signingCertificateDigest.isNullOrBlank() &&
            !artifactVersionCode.isNullOrBlank() &&
            !artifactVersionName.isNullOrBlank()

    private fun String.normalizeDigest(): String = replace(":", "").lowercase()

    private companion object {
        const val SCHEMA_VERSION = 1
        val TARGET_PACKAGES = listOf("com.google.android.gms", "com.android.vending")
        val VERSION_NUMBER = Regex("\\d+")
    }
}
