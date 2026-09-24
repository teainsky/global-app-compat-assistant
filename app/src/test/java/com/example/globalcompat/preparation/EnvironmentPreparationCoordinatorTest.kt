package com.example.globalcompat.preparation

import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.CatalogUpdateStatus
import com.example.globalcompat.catalog.ComponentCatalog
import com.example.globalcompat.catalog.asTestSnapshot
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.CompatibilityDecisionStatus
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.data.GlobalValidationLevel
import com.example.globalcompat.data.PlatformFamily
import com.example.globalcompat.data.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class EnvironmentPreparationCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `normal official pair download verifies both files and becomes ready`() {
        val fixture = fixture()

        val result = fixture.coordinator.prepare(HARMONY_42_REQUEST)

        assertEquals(EnvironmentPreparationStatus.DOWNLOAD_VERIFIED_READY, result.status)
        assertEquals(2, result.preparedComponents.size)
        assertTrue(result.preparedComponents.all { it.file.isFile && it.sizeBytes > 0L })
        assertEquals(2, fixture.transport.calls)
        assertEquals(HARMONY_42_REQUEST.catalogVersion, result.catalogVersion)
        assertEquals(HARMONY_42_REQUEST.catalogDigest, result.catalogDigest)
    }

    @Test
    fun `SHA mismatch deletes every downloaded file and fails closed`() {
        val fixture = fixture(
            catalogTransform = { catalog ->
                catalog.copy(
                    releases = catalog.releases.map { release ->
                        release.copy(
                            artifacts = release.artifacts.mapIndexed { index, artifact ->
                                if (index == 0) artifact.copy(sha256 = "a".repeat(64)) else artifact
                            },
                        )
                    },
                    sourceRecords = catalog.sourceRecords.mapIndexed { index, source ->
                        if (index == 0) source.copy(sourceDigest = "sha256:${"a".repeat(64)}") else source
                    },
                )
            },
        )

        val result = fixture.coordinator.prepare(HARMONY_42_REQUEST)

        assertFailed(result, EnvironmentPreparationFailure.SHA256_MISMATCH, fixture.directory)
    }

    @Test
    fun `signer mismatch fails closed`() {
        val fixture = fixture(
            inspectorTransform = { metadata ->
                metadata.copy(signingCertificateSha256 = listOf("b".repeat(64)))
            },
        )

        val result = fixture.coordinator.prepare(HARMONY_42_REQUEST)

        assertFailed(result, EnvironmentPreparationFailure.SIGNER_MISMATCH, fixture.directory)
    }

    @Test
    fun `package mismatch fails closed`() {
        val fixture = fixture(
            inspectorTransform = { metadata -> metadata.copy(packageName = "invalid.package") },
        )

        val result = fixture.coordinator.prepare(HARMONY_42_REQUEST)

        assertFailed(result, EnvironmentPreparationFailure.PACKAGE_NAME_MISMATCH, fixture.directory)
    }

    @Test
    fun `version mismatch fails closed`() {
        val fixture = fixture(
            inspectorTransform = { metadata -> metadata.copy(versionCode = "1", versionName = "wrong") },
        )

        val result = fixture.coordinator.prepare(HARMONY_42_REQUEST)

        assertFailed(result, EnvironmentPreparationFailure.VERSION_MISMATCH, fixture.directory)
    }

    @Test
    fun `network interruption retries only three times then cleans files`() {
        val fixture = fixture(mode = DownloadMode.NETWORK_FAILURE)

        val result = fixture.coordinator.prepare(HARMONY_42_REQUEST)

        assertFailed(result, EnvironmentPreparationFailure.NETWORK_FAILED, fixture.directory)
        assertEquals(3, fixture.transport.calls)
    }

    @Test
    fun `incomplete download retries then fails closed`() {
        val fixture = fixture(mode = DownloadMode.INCOMPLETE)

        val result = fixture.coordinator.prepare(HARMONY_42_REQUEST)

        assertFailed(result, EnvironmentPreparationFailure.DOWNLOAD_INCOMPLETE, fixture.directory)
        assertEquals(3, fixture.transport.calls)
    }

    @Test
    fun `user cancellation removes partial files`() {
        val fixture = fixture(mode = DownloadMode.CANCEL)

        val result = fixture.coordinator.prepare(HARMONY_42_REQUEST)

        assertEquals(EnvironmentPreparationStatus.CANCELLED, result.status)
        assertTrue(EnvironmentPreparationFailure.USER_CANCELLED in result.failures)
        assertNoFiles(fixture.directory)
    }

    @Test
    fun `catalog signature rejection prevents any download`() {
        val fixture = fixture(catalogStatus = CatalogUpdateStatus.REMOTE_REJECTED)

        val result = fixture.coordinator.prepare(HARMONY_42_REQUEST)

        assertFailed(result, EnvironmentPreparationFailure.CATALOG_NOT_TRUSTED, fixture.directory)
        assertEquals(0, fixture.transport.calls)
    }

    @Test
    fun `HarmonyOS 5 plus never downloads legacy Huawei artifacts`() {
        val fixture = fixture()

        val result = fixture.coordinator.prepare(
            HARMONY_42_REQUEST.copy(
                deviceCategory = DeviceCategory.HARMONYOS_5_PLUS,
                systemVersion = "5.0",
            ),
        )

        assertFailed(result, EnvironmentPreparationFailure.DEVICE_BRANCH_NOT_ALLOWED, fixture.directory)
        assertEquals(0, fixture.transport.calls)
    }

    @Test
    fun `third party compatibility runtime never downloads legacy Huawei artifacts`() {
        val fixture = fixture()
        val result = fixture.coordinator.prepare(
            EXACT_VERIFIED_REQUEST.copy(
                runtimeEnvironment = RuntimeEnvironment.THIRD_PARTY_COMPAT_RUNTIME,
            ),
        )

        assertFailed(
            result,
            EnvironmentPreparationFailure.THIRD_PARTY_COMPAT_RUNTIME_NOT_ALLOWED,
            fixture.directory,
        )
        assertEquals(0, fixture.transport.calls)
    }

    @Test
    fun `unknown HarmonyOS version never downloads legacy Huawei artifacts`() {
        val fixture = fixture()

        val result = fixture.coordinator.prepare(
            HARMONY_42_REQUEST.copy(
                deviceCategory = DeviceCategory.HARMONY_VERSION_UNKNOWN,
                platformFamily = PlatformFamily.HARMONY_VERSION_UNKNOWN,
                systemVersion = null,
            ),
        )

        assertEquals(EnvironmentPreparationStatus.FAIL_CLOSED, result.status)
        assertTrue(EnvironmentPreparationFailure.DEVICE_BRANCH_NOT_ALLOWED in result.failures)
        assertEquals(0, fixture.transport.calls)
    }

    @Test
    fun `verified downloads cannot unlock installation while compatibility is untested`() {
        val fixture = fixture()

        val result = fixture.coordinator.prepare(HARMONY_42_REQUEST)

        assertEquals(EnvironmentPreparationStatus.DOWNLOAD_VERIFIED_READY, result.status)
        assertFalse(result.installationAllowed)
    }

    @Test
    fun `exact verified HBN profile unlocks installation eligibility after preparation`() {
        val fixture = fixture()

        val result = fixture.coordinator.prepare(EXACT_VERIFIED_REQUEST)

        assertEquals(EnvironmentPreparationStatus.DOWNLOAD_VERIFIED_READY, result.status)
        assertTrue(result.installationAllowed)
    }

    @Test
    fun `probable and environment verified downloads never unlock installation`() {
        listOf(
            GlobalValidationLevel.PROBABLE,
            GlobalValidationLevel.ENVIRONMENT_VERIFIED,
        ).forEach { validationLevel ->
            val fixture = fixture()
            val result = fixture.coordinator.prepare(
                EXACT_VERIFIED_REQUEST.copy(validationLevel = validationLevel),
            )

            assertEquals(EnvironmentPreparationStatus.DOWNLOAD_VERIFIED_READY, result.status)
            assertFalse(result.installationAllowed)
        }
    }

    @Test
    fun `device verified without verified workflow decision stays install locked`() {
        val fixture = fixture()

        val result = fixture.coordinator.prepare(
            EXACT_VERIFIED_REQUEST.copy(
                compatibilityDecisionStatus = CompatibilityDecisionStatus.DIAGNOSTIC_ONLY,
            ),
        )

        assertEquals(EnvironmentPreparationStatus.DOWNLOAD_VERIFIED_READY, result.status)
        assertFalse(result.installationAllowed)
    }

    private fun fixture(
        mode: DownloadMode = DownloadMode.NORMAL,
        catalogStatus: CatalogUpdateStatus = CatalogUpdateStatus.REMOTE_VERIFIED,
        catalogTransform: (ComponentCatalog) -> ComponentCatalog = { it },
        inspectorTransform: (DownloadedApkMetadata) -> DownloadedApkMetadata = { it },
    ): Fixture {
        val payloads = BuiltInComponentCatalog.catalog.releases.single().artifacts.associate { artifact ->
            artifact.artifactFilename!! to "test-apk:${artifact.componentId}".toByteArray()
        }
        val catalogWithTestHashes = BuiltInComponentCatalog.catalog.copy(
            releases = BuiltInComponentCatalog.catalog.releases.map { release ->
                release.copy(
                    artifacts = release.artifacts.map { artifact ->
                        artifact.copy(sha256 = sha256(payloads.getValue(artifact.artifactFilename!!)))
                    },
                )
            },
            sourceRecords = BuiltInComponentCatalog.catalog.sourceRecords.map { source ->
                val payload = payloads.getValue(source.observedFilename!!)
                source.copy(
                    expectedSize = payload.size.toLong(),
                    sourceDigest = "sha256:${sha256(payload)}",
                )
            },
        )
        val catalog = catalogTransform(catalogWithTestHashes)
        val artifactsByFilename = catalog.releases.single().artifacts.associateBy {
            it.artifactFilename!!
        }
        val transport = FakeTransport(payloads, mode)
        val inspector = DownloadedApkInspector { file ->
            val artifact = artifactsByFilename.getValue(file.name)
            inspectorTransform(
                DownloadedApkMetadata(
                    packageName = artifact.packageName,
                    versionCode = artifact.artifactVersionCode!!,
                    versionName = artifact.artifactVersionName!!,
                    signingCertificateSha256 = listOf(artifact.signingCertificateDigest!!),
                    signatureVerified = true,
                ),
            )
        }
        val directory = File(temporaryFolder.root, "private-preparation")
        return Fixture(
            coordinator = EnvironmentPreparationCoordinator(
                catalogSnapshot = if (catalogStatus in TRUSTED_STATUSES) {
                    catalog.asTestSnapshot()
                } else {
                    null
                },
                downloadTransport = transport,
                apkInspector = inspector,
                privateTemporaryDirectory = directory,
            ),
            transport = transport,
            directory = directory,
        )
    }

    private fun assertFailed(
        result: EnvironmentPreparationResult,
        expectedFailure: EnvironmentPreparationFailure,
        directory: File,
    ) {
        assertEquals(EnvironmentPreparationStatus.FAIL_CLOSED, result.status)
        assertTrue(expectedFailure in result.failures)
        assertFalse(result.installationAllowed)
        assertNoFiles(directory)
    }

    private fun assertNoFiles(directory: File) {
        assertTrue(!directory.exists() || directory.walkTopDown().none { it.isFile })
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class Fixture(
        val coordinator: EnvironmentPreparationCoordinator,
        val transport: FakeTransport,
        val directory: File,
    )

    private class FakeTransport(
        private val payloads: Map<String, ByteArray>,
        private val mode: DownloadMode,
    ) : OfficialArtifactDownloadTransport {
        var calls: Int = 0
            private set

        override fun download(
            sourceUrl: String,
            destination: File,
            cancellation: PreparationCancellation,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        ): ArtifactDownloadReceipt {
            calls += 1
            if (mode == DownloadMode.NETWORK_FAILURE) throw IOException("connection interrupted")
            val payload = payloads.getValue(destination.name)
            val downloadedPayload = if (mode == DownloadMode.INCOMPLETE) {
                payload.copyOf(payload.size - 1)
            } else {
                payload
            }
            destination.parentFile!!.mkdirs()
            destination.writeBytes(downloadedPayload)
            if (mode == DownloadMode.CANCEL) cancellation.cancel()
            val downloaded = downloadedPayload.size.toLong()
            val expected = payload.size.toLong()
            onProgress(downloaded, expected)
            return ArtifactDownloadReceipt(downloaded, expected)
        }
    }

    private enum class DownloadMode {
        NORMAL,
        NETWORK_FAILURE,
        INCOMPLETE,
        CANCEL,
    }

    private companion object {
        val TRUSTED_STATUSES = setOf(
            CatalogUpdateStatus.BUILT_IN,
            CatalogUpdateStatus.REMOTE_VERIFIED,
        )
        val HARMONY_42_REQUEST = EnvironmentPreparationRequest(
            deviceCategory = DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT,
            platformFamily = PlatformFamily.HARMONY_ANDROID_COMPAT,
            runtimeEnvironment = RuntimeEnvironment.HARMONY_ANDROID_COMPAT,
            planId = CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN,
            deviceModel = "Huawei Pura 70 Pro+",
            systemVersion = "4.2",
            androidApiLevel = 31,
            validationLevel = GlobalValidationLevel.PROBABLE,
            compatibilityDecisionStatus = CompatibilityDecisionStatus.DIAGNOSTIC_ONLY,
            catalogVersion = BuiltInComponentCatalog.catalog.catalogVersion,
            catalogDigest = BuiltInComponentCatalog.catalog.asTestSnapshot().catalogDigest,
        )
        val EXACT_VERIFIED_REQUEST = HARMONY_42_REQUEST.copy(
            deviceModel = "HBN-AL80",
            systemVersion = "4.2.0",
            validationLevel = GlobalValidationLevel.DEVICE_VERIFIED,
            compatibilityDecisionStatus =
                CompatibilityDecisionStatus.VERIFIED_WORKFLOW_AVAILABLE,
        )
    }
}
