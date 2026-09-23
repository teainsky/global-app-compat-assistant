package com.example.globalcompat.installation

import com.example.globalcompat.catalog.CatalogMatchRequest
import com.example.globalcompat.catalog.CatalogSnapshot
import com.example.globalcompat.catalog.CatalogSystemFamily
import com.example.globalcompat.catalog.CatalogVerifiedDeviceCompatibilityRecord
import com.example.globalcompat.catalog.CompatibilityValidationStatus
import com.example.globalcompat.catalog.SourceAvailabilityStatus
import com.example.globalcompat.catalog.TrustedComponentCatalogMatcher
import com.example.globalcompat.data.CompatibilityDecisionStatus
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.data.GlobalValidationLevel
import com.example.globalcompat.data.PlatformFamily
import com.example.globalcompat.data.RomFamily
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKey

data class InstallAuthorizationArtifact(
    val packageName: String,
    val versionCode: String,
    val sha256: String,
    val signerSha256: String,
)

class InstallAuthorization internal constructor(
    val authorizationId: String,
    val deviceProfileDigest: String,
    val model: String,
    val platformFamily: PlatformFamily,
    val osVersion: String,
    val androidApiLevel: Int,
    val catalogVersion: Long,
    val catalogDigest: String,
    val verifiedDeviceRecordDigest: String,
    val compatibilityDecisionDigest: String,
    val workflowId: String,
    val artifacts: List<InstallAuthorizationArtifact>,
    val issuedAt: Long,
    val expiresAt: Long,
    val authorizationProof: String,
)

internal interface InstallAuthorizationSealer {
    fun seal(payload: ByteArray): String

    fun verify(payload: ByteArray, proof: String): Boolean
}

internal class HmacSha256InstallAuthorizationSealer(
    private val keyProvider: () -> SecretKey,
) : InstallAuthorizationSealer {
    override fun seal(payload: ByteArray): String = hmac(payload).toHex()

    override fun verify(payload: ByteArray, proof: String): Boolean {
        val supplied = proof.hexToBytes() ?: return false
        return MessageDigest.isEqual(hmac(payload), supplied)
    }

    private fun hmac(payload: ByteArray): ByteArray = Mac.getInstance(HMAC_ALGORITHM).run {
        init(keyProvider())
        doFinal(payload)
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

internal data class InstallAuthorizationIssueRequest(
    val deviceContext: InstallationDeviceContext,
    val catalogSnapshot: CatalogSnapshot,
    val verifiedDeviceRecord: CatalogVerifiedDeviceCompatibilityRecord,
    val workflowId: String,
    val artifacts: List<InstallAuthorizationArtifact>,
)

internal class InstallAuthorizationIssuer(
    private val sealer: InstallAuthorizationSealer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val authorizationIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val authorizationLifetimeMillis: Long = DEFAULT_AUTHORIZATION_LIFETIME_MILLIS,
) {
    fun issue(request: InstallAuthorizationIssueRequest): InstallAuthorization {
        require(
            request.deviceContext.deviceCategory ==
                DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT &&
                request.deviceContext.platformFamily == PlatformFamily.HARMONY_ANDROID_COMPAT &&
                request.deviceContext.romFamily == RomFamily.HARMONY_OS &&
                request.deviceContext.osVersion.harmonyMajor() in 1..4,
        ) { "InstallAuthorization is restricted to confirmed HarmonyOS 1-4 profiles" }
        val issuedAt = clock()
        val artifacts = request.artifacts
            .map { artifact ->
                artifact.copy(
                    sha256 = artifact.sha256.normalizeDigest(),
                    signerSha256 = artifact.signerSha256.normalizeDigest(),
                )
            }
            .sortedBy(InstallAuthorizationArtifact::packageName)
        require(artifacts.isNotEmpty() && artifacts.distinctBy { it.packageName }.size == artifacts.size)
        require(artifacts.all { it.sha256.isSha256() && it.signerSha256.isSha256() })
        val unsigned = InstallAuthorization(
            authorizationId = authorizationIdFactory(),
            deviceProfileDigest = InstallAuthorizationDigests.deviceProfile(request.deviceContext),
            model = request.deviceContext.model,
            platformFamily = request.deviceContext.platformFamily,
            osVersion = request.deviceContext.osVersion,
            androidApiLevel = request.deviceContext.androidApiLevel,
            catalogVersion = request.catalogSnapshot.catalogVersion,
            catalogDigest = request.catalogSnapshot.catalogDigest.normalizeDigest(),
            verifiedDeviceRecordDigest =
                InstallAuthorizationDigests.verifiedDeviceRecord(request.verifiedDeviceRecord),
            compatibilityDecisionDigest = InstallAuthorizationDigests.compatibilityDecision(
                request.deviceContext,
                request.catalogSnapshot,
                request.workflowId,
            ),
            workflowId = request.workflowId,
            artifacts = artifacts,
            issuedAt = issuedAt,
            expiresAt = issuedAt + authorizationLifetimeMillis,
            authorizationProof = "",
        )
        return unsigned.withProof(sealer.seal(unsigned.canonicalPayload()))
    }

    private companion object {
        const val DEFAULT_AUTHORIZATION_LIFETIME_MILLIS = 15 * 60 * 1000L
    }
}

internal enum class AuthorizationRevalidationStatus {
    AUTHORIZED,
    AUTHORIZED_AFTER_CATALOG_UPDATE,
    AUTHORIZATION_REJECTED,
}

internal enum class AuthorizationRejectionReason {
    INVALID_PROOF,
    EXPIRED,
    DEVICE_PROFILE_CHANGED,
    HARMONYOS_5_PLUS_NOT_SUPPORTED,
    HARMONY_VERSION_UNKNOWN,
    TRUSTED_CATALOG_UNAVAILABLE,
    CATALOG_ROLLBACK,
    CATALOG_DIGEST_CHANGED,
    WORKFLOW_NOT_ALLOWED,
    VERIFIED_DEVICE_RECORD_CHANGED,
    SECURITY_REVOKED,
    ARTIFACT_MISMATCH,
}

internal data class AuthorizationRevalidationResult(
    val status: AuthorizationRevalidationStatus,
    val reason: AuthorizationRejectionReason? = null,
) {
    val isAuthorized: Boolean
        get() = status != AuthorizationRevalidationStatus.AUTHORIZATION_REJECTED
}

internal class AuthorizationRevalidationPolicy(
    private val sealer: InstallAuthorizationSealer,
    private val catalogMatcher: TrustedComponentCatalogMatcher = TrustedComponentCatalogMatcher(),
) {
    fun revalidate(
        authorization: InstallAuthorization,
        currentDevice: InstallationDeviceContext,
        activeSnapshot: CatalogSnapshot?,
        artifacts: List<ExecutableInstallationArtifact>,
        now: Long,
    ): AuthorizationRevalidationResult {
        val proofValid = runCatching {
            sealer.verify(authorization.canonicalPayload(), authorization.authorizationProof)
        }.getOrDefault(false)
        if (!proofValid) {
            return rejected(AuthorizationRejectionReason.INVALID_PROOF)
        }
        if (authorization.issuedAt > now || now >= authorization.expiresAt) {
            return rejected(AuthorizationRejectionReason.EXPIRED)
        }
        if (currentDevice.deviceCategory == DeviceCategory.HARMONY_VERSION_UNKNOWN ||
            currentDevice.platformFamily == PlatformFamily.HARMONY_VERSION_UNKNOWN
        ) {
            return rejected(AuthorizationRejectionReason.HARMONY_VERSION_UNKNOWN)
        }
        if (currentDevice.deviceCategory == DeviceCategory.HARMONYOS_5_PLUS ||
            currentDevice.platformFamily == PlatformFamily.HARMONY_NATIVE
        ) {
            return rejected(AuthorizationRejectionReason.HARMONYOS_5_PLUS_NOT_SUPPORTED)
        }
        if (!authorization.matches(currentDevice)) {
            return rejected(AuthorizationRejectionReason.DEVICE_PROFILE_CHANGED)
        }
        val snapshot = activeSnapshot
            ?: return rejected(AuthorizationRejectionReason.TRUSTED_CATALOG_UNAVAILABLE)
        if (snapshot.catalogVersion < authorization.catalogVersion) {
            return rejected(AuthorizationRejectionReason.CATALOG_ROLLBACK)
        }
        if (snapshot.catalogVersion == authorization.catalogVersion &&
            snapshot.catalogDigest.normalizeDigest() != authorization.catalogDigest.normalizeDigest()
        ) {
            return rejected(AuthorizationRejectionReason.CATALOG_DIGEST_CHANGED)
        }
        if (authorization.workflowId != CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN.name ||
            currentDevice.deviceCategory !in snapshot.catalog.installationGatePolicy.allowedDeviceCategories ||
            currentDevice.platformFamily != PlatformFamily.HARMONY_ANDROID_COMPAT
        ) {
            return rejected(AuthorizationRejectionReason.WORKFLOW_NOT_ALLOWED)
        }

        val record = snapshot.catalog.verifiedDeviceRecords.singleOrNull { candidate ->
            candidate.compatibilityStatus == CompatibilityValidationStatus.DEVICE_VERIFIED &&
                candidate.deviceModel == authorization.model &&
                candidate.deviceFamily == authorization.model &&
                candidate.romFamily == currentDevice.romFamily.name &&
                candidate.harmonyOsVersion == authorization.osVersion &&
                candidate.androidApiLevel == authorization.androidApiLevel
        } ?: return rejected(AuthorizationRejectionReason.VERIFIED_DEVICE_RECORD_CHANGED)
        if (InstallAuthorizationDigests.verifiedDeviceRecord(record) !=
            authorization.verifiedDeviceRecordDigest
        ) {
            return rejected(AuthorizationRejectionReason.VERIFIED_DEVICE_RECORD_CHANGED)
        }
        val expectedDecisionDigest = InstallAuthorizationDigests.compatibilityDecision(
            currentDevice,
            CatalogSnapshot(
                catalogVersion = authorization.catalogVersion,
                catalogDigest = authorization.catalogDigest,
                source = snapshot.source,
                schemaVersion = snapshot.schemaVersion,
                loadedAt = snapshot.loadedAt,
                catalog = snapshot.catalog,
            ),
            authorization.workflowId,
        )
        if (expectedDecisionDigest != authorization.compatibilityDecisionDigest) {
            return rejected(AuthorizationRejectionReason.INVALID_PROOF)
        }

        val release = snapshot.catalog.releases.singleOrNull {
            it.releaseTag == record.componentRelease
        } ?: return rejected(AuthorizationRejectionReason.WORKFLOW_NOT_ALLOWED)
        val authorizedByPackage = authorization.artifacts.associateBy { it.packageName }
        val revoked = release.artifacts.any { artifact ->
            artifact.packageName in authorizedByPackage &&
                snapshot.catalog.blockedVersions.any { rule ->
                    rule.componentId == artifact.componentId &&
                        (artifact.releaseVersion in rule.versions ||
                            artifact.artifactVersionCode in rule.versions ||
                            artifact.artifactVersionName in rule.versions)
                }
        }
        if (revoked) return rejected(AuthorizationRejectionReason.SECURITY_REVOKED)

        val selection = catalogMatcher.select(
            snapshot.catalog,
            CatalogMatchRequest(
                planId = CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN,
                deviceCategory = currentDevice.deviceCategory,
                deviceFamily = currentDevice.model,
                systemFamily = CatalogSystemFamily.HUAWEI_HARMONY_OS,
                systemVersion = currentDevice.osVersion,
                androidApiLevel = currentDevice.androidApiLevel,
            ),
        )
        val installableByPackage = selection.installableArtifacts.associateBy { it.packageName }
        val catalogArtifactsMatch = authorization.artifacts.all { authorized ->
            val catalogArtifact = installableByPackage[authorized.packageName]
                ?: return@all false
            catalogArtifact.artifactVersionCode == authorized.versionCode &&
                catalogArtifact.sha256?.normalizeDigest() == authorized.sha256.normalizeDigest() &&
                catalogArtifact.signingCertificateDigest?.normalizeDigest() ==
                authorized.signerSha256.normalizeDigest() &&
                snapshot.catalog.sourceRecords.count { source ->
                    source.componentId == catalogArtifact.componentId &&
                        source.sourceType == catalogArtifact.sourceType &&
                        source.availabilityStatus == SourceAvailabilityStatus.AVAILABLE &&
                        source.sourceAssetId == catalogArtifact.githubAssetId &&
                        source.observedFilename == catalogArtifact.artifactFilename
                } == 1
        }
        if (!catalogArtifactsMatch) {
            return rejected(AuthorizationRejectionReason.ARTIFACT_MISMATCH)
        }
        val prepared = artifacts.map {
            InstallAuthorizationArtifact(
                packageName = it.packageName,
                versionCode = it.expectedVersionCode,
                sha256 = it.expectedSha256.normalizeDigest(),
                signerSha256 = it.expectedSigningCertificateSha256.normalizeDigest(),
            )
        }.sortedBy(InstallAuthorizationArtifact::packageName)
        if (prepared != authorization.artifacts.sortedBy(InstallAuthorizationArtifact::packageName)) {
            return rejected(AuthorizationRejectionReason.ARTIFACT_MISMATCH)
        }
        return AuthorizationRevalidationResult(
            status = if (snapshot.catalogVersion == authorization.catalogVersion) {
                AuthorizationRevalidationStatus.AUTHORIZED
            } else {
                AuthorizationRevalidationStatus.AUTHORIZED_AFTER_CATALOG_UPDATE
            },
        )
    }

    private fun rejected(reason: AuthorizationRejectionReason) =
        AuthorizationRevalidationResult(
            status = AuthorizationRevalidationStatus.AUTHORIZATION_REJECTED,
            reason = reason,
        )
}

internal object InstallAuthorizationDigests {
    fun deviceProfile(device: InstallationDeviceContext): String = digest(
        listOf(
            "deviceCategory" to device.deviceCategory.name,
            "manufacturer" to device.manufacturer.trim().lowercase(),
            "model" to device.model,
            "platformFamily" to device.platformFamily.name,
            "osVersion" to device.osVersion,
            "androidApiLevel" to device.androidApiLevel.toString(),
            "romFamily" to device.romFamily.name,
            "romVersion" to device.romVersion.orEmpty(),
        ),
    )

    fun verifiedDeviceRecord(record: CatalogVerifiedDeviceCompatibilityRecord): String = digest(
        buildList {
            add("schemaVersion" to record.schemaVersion.toString())
            add("deviceModel" to record.deviceModel)
            add("deviceFamily" to record.deviceFamily)
            add("romFamily" to record.romFamily)
            add("harmonyOsVersion" to record.harmonyOsVersion)
            add("androidApiLevel" to record.androidApiLevel.toString())
            add("componentRelease" to record.componentRelease)
            record.componentVersionCodes.toSortedMap().forEach { (name, version) ->
                add("componentVersionCode.$name" to version)
            }
            record.componentSignerDigests.toSortedMap().forEach { (name, signers) ->
                add("componentSignerDigests.$name" to signers.map(String::normalizeDigest).sorted().joinToString(","))
            }
            add("validationDate" to record.validationDate)
            add("evidenceDigest" to record.evidenceDigest.normalizeDigest())
            add("compatibilityStatus" to record.compatibilityStatus.name)
        },
    )

    fun compatibilityDecision(
        device: InstallationDeviceContext,
        snapshot: CatalogSnapshot,
        workflowId: String,
    ): String = digest(
        listOf(
            "decisionStatus" to CompatibilityDecisionStatus.VERIFIED_WORKFLOW_AVAILABLE.name,
            "validationLevel" to GlobalValidationLevel.DEVICE_VERIFIED.name,
            "workflowId" to workflowId,
            "deviceProfileDigest" to deviceProfile(device),
            "catalogVersion" to snapshot.catalogVersion.toString(),
            "catalogDigest" to snapshot.catalogDigest.normalizeDigest(),
        ),
    )

    private fun digest(fields: List<Pair<String, String>>): String = sha256(
        canonicalFields(fields).toByteArray(Charsets.UTF_8),
    )
}

private fun InstallAuthorization.matches(device: InstallationDeviceContext): Boolean =
    model == device.model &&
        platformFamily == device.platformFamily &&
        osVersion == device.osVersion &&
        androidApiLevel == device.androidApiLevel &&
        deviceProfileDigest == InstallAuthorizationDigests.deviceProfile(device)

private fun InstallAuthorization.withProof(proof: String) = InstallAuthorization(
    authorizationId = authorizationId,
    deviceProfileDigest = deviceProfileDigest,
    model = model,
    platformFamily = platformFamily,
    osVersion = osVersion,
    androidApiLevel = androidApiLevel,
    catalogVersion = catalogVersion,
    catalogDigest = catalogDigest,
    verifiedDeviceRecordDigest = verifiedDeviceRecordDigest,
    compatibilityDecisionDigest = compatibilityDecisionDigest,
    workflowId = workflowId,
    artifacts = artifacts,
    issuedAt = issuedAt,
    expiresAt = expiresAt,
    authorizationProof = proof,
)

private fun InstallAuthorization.canonicalPayload(): ByteArray = canonicalFields(
    buildList {
        add("authorizationId" to authorizationId)
        add("deviceProfileDigest" to deviceProfileDigest)
        add("model" to model)
        add("platformFamily" to platformFamily.name)
        add("osVersion" to osVersion)
        add("androidApiLevel" to androidApiLevel.toString())
        add("catalogVersion" to catalogVersion.toString())
        add("catalogDigest" to catalogDigest.normalizeDigest())
        add("verifiedDeviceRecordDigest" to verifiedDeviceRecordDigest)
        add("compatibilityDecisionDigest" to compatibilityDecisionDigest)
        add("workflowId" to workflowId)
        artifacts.sortedBy(InstallAuthorizationArtifact::packageName).forEachIndexed { index, artifact ->
            add("artifacts.$index.packageName" to artifact.packageName)
            add("artifacts.$index.versionCode" to artifact.versionCode)
            add("artifacts.$index.sha256" to artifact.sha256.normalizeDigest())
            add("artifacts.$index.signerSha256" to artifact.signerSha256.normalizeDigest())
        }
        add("issuedAt" to issuedAt.toString())
        add("expiresAt" to expiresAt.toString())
    },
).toByteArray(Charsets.UTF_8)

private fun canonicalFields(fields: List<Pair<String, String>>): String = buildString {
    fields.forEach { (name, value) ->
        append(name.length).append(':').append(name)
        append('=').append(value.length).append(':').append(value).append('\n')
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .toHex()

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

private fun String.hexToBytes(): ByteArray? {
    val normalized = normalizeDigest()
    if (normalized.length % 2 != 0 || normalized.any { it !in "0123456789abcdef" }) return null
    return ByteArray(normalized.length / 2) { index ->
        normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun String.normalizeDigest(): String = replace(":", "").lowercase()

private fun String.harmonyMajor(): Int? = substringBefore('.').toIntOrNull()

private fun String.isSha256(): Boolean = SHA256.matches(normalizeDigest())

private val SHA256 = Regex("[0-9a-f]{64}")
