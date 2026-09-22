package com.example.globalcompat.installation

import com.example.globalcompat.catalog.ComponentSourceType
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.data.RomFamily
import com.example.globalcompat.preparation.EnvironmentPreparationResult
import com.example.globalcompat.preparation.EnvironmentPreparationStatus
import com.example.globalcompat.preparation.PreparedEnvironmentComponent
import com.example.globalcompat.simulation.SimulatedInstallAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstallationExecutorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `device not verified keeps real installation entry blocked`() {
        val fixture = fixture(permissionGranted = true)
        val request = fixture.request.copy(
            sessionPlan = fixture.request.sessionPlan.copy(
                status = InstallationSessionStatus.BLOCKED,
                executionAllowed = false,
                blockReasons = listOf(
                    InstallationBlockReason.COMPATIBILITY_NOT_DEVICE_VERIFIED,
                ),
            ),
            preparationResult = fixture.request.preparationResult.copy(
                installationAllowed = false,
            ),
        )

        val result = fixture.executor.begin(request)

        assertEquals(InstallationExecutionState.BLOCKED, result.state)
        assertEquals(InstallationExecutionFailure.EXECUTION_GATE_BLOCKED, result.failure)
        assertTrue(fixture.gateway.committedComponents.isEmpty())
    }

    @Test
    fun `missing install source permission waits without creating session`() {
        val fixture = fixture(permissionGranted = false)

        val result = fixture.executor.begin(fixture.request)

        assertEquals(InstallationExecutionState.WAITING_FOR_INSTALL_PERMISSION, result.state)
        assertTrue(fixture.gateway.committedComponents.isEmpty())
    }

    @Test
    fun `user permission approval makes session ready then requests system confirmation`() {
        val fixture = fixture(permissionGranted = false)
        fixture.executor.begin(fixture.request)
        fixture.gateway.permissionGranted = true

        val ready = fixture.executor.onInstallPermissionResult()
        val installing = fixture.executor.continueExecution()
        val pending = fixture.executor.onPackageInstallerEvent(
            logicalSessionId = checkNotNull(installing).logicalSessionId,
            packageInstallerSessionId = checkNotNull(installing.packageInstallerSessionId),
            event = PackageInstallerEvent.PENDING_USER_ACTION,
        )

        assertEquals(InstallationExecutionState.READY, ready?.state)
        assertEquals(InstallationExecutionState.WAITING_FOR_USER_CONFIRMATION, pending?.state)
        assertEquals(listOf(GMS_COMPONENT_ID), fixture.gateway.committedComponents)
    }

    @Test
    fun `user cancellation safely stops without second component`() {
        val fixture = fixture(permissionGranted = true)
        fixture.executor.begin(fixture.request)
        val installing = checkNotNull(fixture.executor.continueExecution())

        val result = fixture.executor.onPackageInstallerEvent(
            installing.logicalSessionId,
            checkNotNull(installing.packageInstallerSessionId),
            PackageInstallerEvent.USER_CANCELLED,
        )

        assertEquals(InstallationExecutionState.CANCELLED, result?.state)
        assertEquals(listOf(GMS_COMPONENT_ID), fixture.gateway.committedComponents)
    }

    @Test
    fun `first component installation failure never commits second component`() {
        val fixture = fixture(permissionGranted = true)
        fixture.executor.begin(fixture.request)
        val installing = checkNotNull(fixture.executor.continueExecution())

        val result = fixture.executor.onPackageInstallerEvent(
            installing.logicalSessionId,
            checkNotNull(installing.packageInstallerSessionId),
            PackageInstallerEvent.FAILURE,
        )

        assertEquals(InstallationExecutionState.FAILED, result?.state)
        assertEquals(
            InstallationExecutionFailure.PACKAGE_INSTALLER_FAILURE,
            result?.failure,
        )
        assertEquals(listOf(GMS_COMPONENT_ID), fixture.gateway.committedComponents)
    }

    @Test
    fun `first component post install mismatch fails closed before second component`() {
        val fixture = fixture(permissionGranted = true)
        fixture.installedVerifier.rejectedPackages += GMS_PACKAGE
        fixture.executor.begin(fixture.request)
        val installing = checkNotNull(fixture.executor.continueExecution())

        val result = fixture.executor.onPackageInstallerEvent(
            installing.logicalSessionId,
            checkNotNull(installing.packageInstallerSessionId),
            PackageInstallerEvent.SUCCESS,
        )

        assertEquals(InstallationExecutionState.FAILED, result?.state)
        assertEquals(
            InstallationExecutionFailure.POST_INSTALL_VERIFICATION_FAILED,
            result?.failure,
        )
        assertEquals(listOf(GMS_COMPONENT_ID), fixture.gateway.committedComponents)
    }

    @Test
    fun `two components install and verify in catalog plan order`() {
        val fixture = fixture(permissionGranted = true)
        fixture.executor.begin(fixture.request)

        val first = checkNotNull(fixture.executor.continueExecution())
        val firstVerified = fixture.executor.onPackageInstallerEvent(
            first.logicalSessionId,
            checkNotNull(first.packageInstallerSessionId),
            PackageInstallerEvent.SUCCESS,
        )
        val second = checkNotNull(fixture.executor.continueExecution())
        val completed = fixture.executor.onPackageInstallerEvent(
            second.logicalSessionId,
            checkNotNull(second.packageInstallerSessionId),
            PackageInstallerEvent.SUCCESS,
        )

        assertEquals(InstallationExecutionState.READY, firstVerified?.state)
        assertEquals(InstallationExecutionState.COMPLETED, completed?.state)
        assertEquals(
            listOf(GMS_COMPONENT_ID, VENDING_COMPONENT_ID),
            fixture.gateway.committedComponents,
        )
        assertTrue(fixture.finalVerifier.called)
    }

    @Test
    fun `app restart restores pending session and never repeats first install`() {
        val fixture = fixture(permissionGranted = true)
        fixture.executor.begin(fixture.request)
        val first = checkNotNull(fixture.executor.continueExecution())
        fixture.executor.onPackageInstallerEvent(
            first.logicalSessionId,
            checkNotNull(first.packageInstallerSessionId),
            PackageInstallerEvent.PENDING_USER_ACTION,
        )
        val restarted = fixture.newExecutor()

        val restored = restarted.restore()
        restarted.onPackageInstallerEvent(
            checkNotNull(restored).logicalSessionId,
            checkNotNull(restored.packageInstallerSessionId),
            PackageInstallerEvent.SUCCESS,
        )
        restarted.continueExecution()

        assertEquals(
            listOf(GMS_COMPONENT_ID, VENDING_COMPONENT_ID),
            fixture.gateway.committedComponents,
        )
        assertEquals(1, fixture.gateway.committedComponents.count { it == GMS_COMPONENT_ID })
    }

    @Test
    fun `HarmonyOS 5 plus cannot enter legacy installer even with forged ready plan`() {
        val fixture = fixture(permissionGranted = true)
        val request = fixture.request.copy(
            deviceContext = fixture.request.deviceContext.copy(
                deviceCategory = DeviceCategory.HARMONYOS_5_PLUS,
                romFamily = RomFamily.HARMONY_OS_5_PLUS,
                romVersion = "5.0",
            ),
        )

        val result = fixture.executor.begin(request)

        assertEquals(InstallationExecutionState.BLOCKED, result.state)
        assertEquals(
            InstallationExecutionFailure.HARMONYOS_5_PLUS_NOT_SUPPORTED,
            result.failure,
        )
        assertFalse(result.artifacts.isNotEmpty())
        assertTrue(fixture.gateway.committedComponents.isEmpty())
    }

    private fun fixture(permissionGranted: Boolean): Fixture {
        val preparedComponents = listOf(
            prepared(GMS_COMPONENT_ID, GMS_PACKAGE, GMS_FILENAME, "gms"),
            prepared(VENDING_COMPONENT_ID, VENDING_PACKAGE, VENDING_FILENAME, "vending"),
        )
        val plan = readyPlan(preparedComponents)
        val request = InstallationExecutionRequest(
            deviceContext = InstallationDeviceContext(
                deviceCategory = DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT,
                manufacturer = "Huawei",
                romFamily = RomFamily.HARMONY_OS,
                romVersion = "4.2",
            ),
            sessionPlan = plan,
            preparationResult = EnvironmentPreparationResult(
                status = EnvironmentPreparationStatus.DOWNLOAD_VERIFIED_READY,
                preparedComponents = preparedComponents,
                failures = emptyList(),
                installationAllowed = true,
                technicalDetails = emptyList(),
                catalogVersion = CATALOG_VERSION,
                catalogDigest = CATALOG_DIGEST,
            ),
        )
        val store = MemoryStore()
        val gateway = FakeGateway(permissionGranted)
        val installedVerifier = FakeInstalledVerifier()
        val finalVerifier = FakeFinalVerifier()
        return Fixture(
            request = request,
            store = store,
            gateway = gateway,
            installedVerifier = installedVerifier,
            finalVerifier = finalVerifier,
            executor = executor(store, gateway, installedVerifier, finalVerifier),
        )
    }

    private fun executor(
        store: MemoryStore,
        gateway: FakeGateway,
        installedVerifier: FakeInstalledVerifier,
        finalVerifier: FakeFinalVerifier,
    ) = InstallationExecutor(
        sessionStore = store,
        packageInstallerGateway = gateway,
        preparedArtifactRevalidator = PreparedArtifactRevalidator {
            PreparedArtifactValidation(true, "verified")
        },
        installedComponentVerifier = installedVerifier,
        finalEnvironmentVerifier = finalVerifier,
        clock = { 1_700_000_000_000L },
        sessionIdFactory = { "test-session" },
    )

    private fun prepared(
        componentId: String,
        packageName: String,
        filename: String,
        content: String,
    ): PreparedEnvironmentComponent {
        val file = temporaryFolder.newFile(filename).apply { writeText(content) }
        return PreparedEnvironmentComponent(
            componentId = componentId,
            packageName = packageName,
            artifactFilename = filename,
            file = file,
            sizeBytes = file.length(),
        )
    }

    private fun readyPlan(
        prepared: List<PreparedEnvironmentComponent>,
    ): InstallationSessionPlan {
        val specs = listOf(
            ComponentSpec(
                GMS_COMPONENT_ID,
                GMS_PACKAGE,
                GMS_FILENAME,
                "252432032",
                "0.3.16.252432-hw",
            ),
            ComponentSpec(
                VENDING_COMPONENT_ID,
                VENDING_PACKAGE,
                VENDING_FILENAME,
                "84022632",
                "0.3.16.40226-hw",
            ),
        )
        return InstallationSessionPlan(
            schemaVersion = 1,
            status = InstallationSessionStatus.READY_FOR_USER_CONFIRMATION,
            executionAllowed = true,
            userConfirmationRequired = true,
            userMessage = "ready",
            steps = specs.mapIndexed { index, spec ->
                val preparedComponent = prepared.single { it.componentId == spec.componentId }
                InstallationSessionStep(
                    componentId = spec.componentId,
                    packageName = spec.packageName,
                    action = SimulatedInstallAction.INSTALL,
                    order = index + 1,
                    state = InstallationStepState.READY_FOR_USER_CONFIRMATION,
                    downloadRequest = ArtifactDownloadRequest(
                        componentId = spec.componentId,
                        packageName = spec.packageName,
                        releaseTag = "v0.3.16.252432",
                        artifactFilename = spec.filename,
                        artifactVersionCode = spec.versionCode,
                        artifactVersionName = spec.versionName,
                        sourceType = ComponentSourceType.OFFICIAL_MICROG_GITHUB,
                        sourceUrl = "https://github.com/microg/GmsCore/releases/download/tag/${spec.filename}",
                        sourceAssetId = (index + 1).toLong(),
                        expectedSha256 = SHA256,
                        expectedSigningCertificateSha256 = SIGNER,
                        catalogVersion = CATALOG_VERSION,
                        catalogDigest = CATALOG_DIGEST,
                    ),
                    verificationResult = ArtifactVerificationResult(
                        componentId = spec.componentId,
                        downloadEvidencePresent = true,
                        sourceAssetId = (index + 1).toLong(),
                        artifactFilename = preparedComponent.artifactFilename,
                        locallyCalculatedSha256 = SHA256,
                        signingCertificateSha256 = listOf(SIGNER),
                        apkSignatureVerificationPassed = true,
                        packageName = spec.packageName,
                        versionCode = spec.versionCode,
                        versionName = spec.versionName,
                    ),
                    blockReasons = emptyList(),
                )
            },
            blockReasons = emptyList(),
            catalogVersion = CATALOG_VERSION,
            catalogDigest = CATALOG_DIGEST,
        )
    }

    private data class ComponentSpec(
        val componentId: String,
        val packageName: String,
        val filename: String,
        val versionCode: String,
        val versionName: String,
    )

    private data class Fixture(
        val request: InstallationExecutionRequest,
        val store: MemoryStore,
        val gateway: FakeGateway,
        val installedVerifier: FakeInstalledVerifier,
        val finalVerifier: FakeFinalVerifier,
        val executor: InstallationExecutor,
    ) {
        fun newExecutor(): InstallationExecutor = InstallationExecutor(
            sessionStore = store,
            packageInstallerGateway = gateway,
            preparedArtifactRevalidator = PreparedArtifactRevalidator {
                PreparedArtifactValidation(true, "verified")
            },
            installedComponentVerifier = installedVerifier,
            finalEnvironmentVerifier = finalVerifier,
            clock = { 1_700_000_000_001L },
            sessionIdFactory = { "restarted-session" },
        )
    }

    private class MemoryStore : InstallationSessionStore {
        private var snapshot: InstallationExecutionSnapshot? = null

        override fun load(): InstallationExecutionSnapshot? = snapshot

        override fun save(snapshot: InstallationExecutionSnapshot) {
            this.snapshot = snapshot
        }

        override fun clear() {
            snapshot = null
        }
    }

    private class FakeGateway(
        var permissionGranted: Boolean,
    ) : PackageInstallerGateway {
        val committedComponents = mutableListOf<String>()
        val activeSessions = mutableSetOf<Int>()

        override fun hasInstallPermission(): Boolean = permissionGranted

        override fun commit(
            artifact: ExecutableInstallationArtifact,
            logicalSessionId: String,
        ): Int {
            committedComponents += artifact.componentId
            return (100 + committedComponents.size).also(activeSessions::add)
        }

        override fun isSessionActive(packageInstallerSessionId: Int): Boolean =
            packageInstallerSessionId in activeSessions
    }

    private class FakeInstalledVerifier : InstalledComponentPostVerifier {
        var rejectedPackages = emptySet<String>()

        override fun verify(
            artifact: ExecutableInstallationArtifact,
            deviceContext: InstallationDeviceContext,
        ): InstalledComponentVerification = InstalledComponentVerification(
            installed = true,
            enabled = true,
            packageName = artifact.packageName,
            versionCode = artifact.expectedVersionCode,
            versionName = artifact.expectedVersionName,
            reportedSigningCertificateSha256 = listOf(
                artifact.expectedSigningCertificateSha256,
            ),
            reportedSignerAccepted = artifact.packageName !in rejectedPackages,
        )
    }

    private class FakeFinalVerifier : FinalEnvironmentVerifier {
        var called = false

        override fun verify(deviceContext: InstallationDeviceContext): Boolean {
            called = true
            return true
        }
    }

    private companion object {
        const val GMS_COMPONENT_ID = "microg_services_huawei_compatible"
        const val VENDING_COMPONENT_ID = "microg_companion_huawei_compatible"
        const val GMS_PACKAGE = "com.google.android.gms"
        const val VENDING_PACKAGE = "com.android.vending"
        const val GMS_FILENAME = "com.google.android.gms-252432032-hw.apk"
        const val VENDING_FILENAME = "com.android.vending-84022632-hw.apk"
        const val SHA256 =
            "a44ce933e2336d3340eb82ad3bb28bba03bc56a7b3cf3c98250a225c55b572de"
        const val SIGNER =
            "9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165"
        const val CATALOG_VERSION = 1L
        const val CATALOG_DIGEST =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
