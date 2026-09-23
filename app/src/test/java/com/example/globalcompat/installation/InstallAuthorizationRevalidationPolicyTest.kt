package com.example.globalcompat.installation

import com.example.globalcompat.catalog.BlockedVersionRule
import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.CatalogSnapshot
import com.example.globalcompat.catalog.asTestSnapshot
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.data.PlatformFamily
import com.example.globalcompat.data.RomFamily
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallAuthorizationRevalidationPolicyTest {
    private val snapshot = BuiltInComponentCatalog.catalog.asTestSnapshot()
    private val sealer = testInstallAuthorizationSealer()
    private val policy = AuthorizationRevalidationPolicy(sealer)
    private val device = InstallationDeviceContext(
        deviceCategory = DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT,
        manufacturer = "Huawei",
        model = "HBN-AL80",
        platformFamily = PlatformFamily.HARMONY_ANDROID_COMPAT,
        osVersion = "4.2.0",
        androidApiLevel = 31,
        romFamily = RomFamily.HARMONY_OS,
        romVersion = "4.2.0",
    )
    private val artifacts = snapshot.catalog.releases.single().artifacts.map { artifact ->
        ExecutableInstallationArtifact(
            componentId = artifact.componentId,
            packageName = artifact.packageName,
            artifactFilename = requireNotNull(artifact.artifactFilename),
            filePath = "unused/${artifact.artifactFilename}",
            expectedSizeBytes = 1,
            expectedSha256 = requireNotNull(artifact.sha256),
            expectedVersionCode = requireNotNull(artifact.artifactVersionCode),
            expectedVersionName = requireNotNull(artifact.artifactVersionName),
            expectedSigningCertificateSha256 =
                requireNotNull(artifact.signingCertificateDigest),
        )
    }

    @Test
    fun `normal exact HBN profile authorization is accepted`() {
        val authorization = authorization()

        val result = revalidate(authorization)

        assertEquals(AuthorizationRevalidationStatus.AUTHORIZED, result.status)
        assertEquals("HBN-AL80", authorization.model)
        assertEquals("4.2.0", authorization.osVersion)
        assertEquals(31, authorization.androidApiLevel)
        assertEquals(snapshot.catalogVersion, authorization.catalogVersion)
        assertEquals(snapshot.catalogDigest, authorization.catalogDigest)
        assertEquals(2, authorization.artifacts.size)
    }

    @Test
    fun `model change rejects authorization`() {
        assertRejected(
            device = device.copy(model = "HBN-AL90"),
            expected = AuthorizationRejectionReason.DEVICE_PROFILE_CHANGED,
        )
    }

    @Test
    fun `API change rejects authorization`() {
        assertRejected(
            device = device.copy(androidApiLevel = 32),
            expected = AuthorizationRejectionReason.DEVICE_PROFILE_CHANGED,
        )
    }

    @Test
    fun `OS version change rejects authorization`() {
        assertRejected(
            device = device.copy(osVersion = "4.3", romVersion = "4.3"),
            expected = AuthorizationRejectionReason.DEVICE_PROFILE_CHANGED,
        )
    }

    @Test
    fun `same catalog version with changed digest rejects authorization`() {
        val result = revalidate(
            authorization = authorization(),
            activeSnapshot = snapshot.copy(catalogDigest = RANDOM_SHA256),
        )

        assertEquals(AuthorizationRevalidationStatus.AUTHORIZATION_REJECTED, result.status)
        assertEquals(AuthorizationRejectionReason.CATALOG_DIGEST_CHANGED, result.reason)
    }

    @Test
    fun `ordinary newer catalog preserves authorization only after full revalidation`() {
        val newerCatalog = snapshot.catalog.copy(catalogVersion = snapshot.catalogVersion + 1)

        val result = revalidate(
            authorization = authorization(),
            activeSnapshot = newerCatalog.asTestSnapshot(),
        )

        assertEquals(
            AuthorizationRevalidationStatus.AUTHORIZED_AFTER_CATALOG_UPDATE,
            result.status,
        )
    }

    @Test
    fun `artifact hash change rejects authorization`() {
        val changed = artifacts.toMutableList().apply {
            this[0] = this[0].copy(expectedSha256 = RANDOM_SHA256)
        }

        val result = revalidate(authorization(), artifacts = changed)

        assertEquals(AuthorizationRejectionReason.ARTIFACT_MISMATCH, result.reason)
    }

    @Test
    fun `artifact signer change rejects authorization`() {
        val changed = artifacts.toMutableList().apply {
            this[0] = this[0].copy(expectedSigningCertificateSha256 = RANDOM_SHA256)
        }

        val result = revalidate(authorization(), artifacts = changed)

        assertEquals(AuthorizationRejectionReason.ARTIFACT_MISMATCH, result.reason)
    }

    @Test
    fun `expired authorization is rejected`() {
        val result = revalidate(
            authorization = authorization(),
            now = ISSUED_AT + 15 * 60 * 1000L,
        )

        assertEquals(AuthorizationRejectionReason.EXPIRED, result.reason)
    }

    @Test
    fun `new catalog blocking an authorized version revokes old authorization`() {
        val gms = snapshot.catalog.releases.single().artifacts.single {
            it.packageName == "com.google.android.gms"
        }
        val revokedCatalog = snapshot.catalog.copy(
            catalogVersion = snapshot.catalogVersion + 1,
            blockedVersions = listOf(
                BlockedVersionRule(
                    componentId = gms.componentId,
                    versions = setOf(requireNotNull(gms.artifactVersionCode)),
                    reason = "security revocation",
                ),
            ),
        )

        val result = revalidate(
            authorization = authorization(),
            activeSnapshot = revokedCatalog.asTestSnapshot(),
        )

        assertEquals(AuthorizationRejectionReason.SECURITY_REVOKED, result.reason)
    }

    @Test
    fun `authorization forged through ordinary JSON is rejected`() {
        val gson = Gson()
        val tree = gson.toJsonTree(authorization()).asJsonObject
        tree.addProperty("model", "forged-model")
        val forged = gson.fromJson(tree, InstallAuthorization::class.java)

        val result = revalidate(forged)

        assertEquals(AuthorizationRejectionReason.INVALID_PROOF, result.reason)
    }

    @Test
    fun `Harmony native profile cannot reuse old Harmony authorization`() {
        val harmonyNative = device.copy(
            deviceCategory = DeviceCategory.HARMONYOS_5_PLUS,
            platformFamily = PlatformFamily.HARMONY_NATIVE,
            osVersion = "6.0",
            romFamily = RomFamily.HARMONY_OS_5_PLUS,
            romVersion = "6.0",
        )

        val result = revalidate(authorization(), device = harmonyNative)

        assertTrue(!result.isAuthorized)
    }

    private fun authorization(): InstallAuthorization = InstallAuthorizationIssuer(
        sealer = sealer,
        clock = { ISSUED_AT },
        authorizationIdFactory = { "authorization-test-id" },
    ).issue(
        InstallAuthorizationIssueRequest(
            deviceContext = device,
            catalogSnapshot = snapshot,
            verifiedDeviceRecord = snapshot.catalog.verifiedDeviceRecords.single(),
            workflowId = "HUAWEI_MICROG_COMPAT_PLAN",
            artifacts = artifacts.map {
                InstallAuthorizationArtifact(
                    packageName = it.packageName,
                    versionCode = it.expectedVersionCode,
                    sha256 = it.expectedSha256,
                    signerSha256 = it.expectedSigningCertificateSha256,
                )
            },
        ),
    )

    private fun revalidate(
        authorization: InstallAuthorization,
        device: InstallationDeviceContext = this.device,
        activeSnapshot: CatalogSnapshot? = snapshot,
        artifacts: List<ExecutableInstallationArtifact> = this.artifacts,
        now: Long = ISSUED_AT + 1,
    ): AuthorizationRevalidationResult = policy.revalidate(
        authorization = authorization,
        currentDevice = device,
        activeSnapshot = activeSnapshot,
        artifacts = artifacts,
        now = now,
    )

    private fun assertRejected(
        device: InstallationDeviceContext,
        expected: AuthorizationRejectionReason,
    ) {
        val result = revalidate(authorization(), device = device)
        assertEquals(AuthorizationRevalidationStatus.AUTHORIZATION_REJECTED, result.status)
        assertEquals(expected, result.reason)
    }

    private companion object {
        const val ISSUED_AT = 1_700_000_000_000L
        const val RANDOM_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
