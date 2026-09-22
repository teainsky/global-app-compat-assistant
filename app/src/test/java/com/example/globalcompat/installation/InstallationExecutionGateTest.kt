package com.example.globalcompat.installation

import com.example.globalcompat.catalog.ArtifactIntegrityStatus
import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.CompatibilityValidationStatus
import com.example.globalcompat.catalog.ComponentCatalog
import com.example.globalcompat.catalog.SourceAvailabilityStatus
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.data.GlobalValidationLevel
import com.example.globalcompat.simulation.CurrentComponentDecision
import com.example.globalcompat.simulation.CurrentComponentState
import com.example.globalcompat.simulation.SimulatedArtifact
import com.example.globalcompat.simulation.SimulatedInstallAction
import com.example.globalcompat.simulation.SimulatedInstallationPlan
import com.example.globalcompat.simulation.SimulatedInstallationStep
import com.example.globalcompat.simulation.SimulationNextAction
import com.example.globalcompat.simulation.SimulationPlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationExecutionGateTest {
    private val builtIn = BuiltInComponentCatalog.catalog

    @Test
    fun `already installed pair completes without execution`() {
        val plan = simulatedPlan(
            catalog = builtIn,
            states = officialPairStates(),
        )

        val result = InstallationExecutionGate(builtIn).evaluate(plan)

        assertEquals(InstallationSessionStatus.NO_ACTION_REQUIRED, result.status)
        assertFalse(result.executionAllowed)
        assertFalse(result.userConfirmationRequired)
        assertTrue(result.steps.all { it.state == InstallationStepState.ALREADY_COMPLETED })
    }

    @Test
    fun `only missing GmsCore can reach future user confirmation when every gate passes`() {
        assertSingleReadyComponent(
            missingComponentId = GMS_COMPONENT_ID,
            completedComponentId = VENDING_COMPONENT_ID,
        )
    }

    @Test
    fun `only missing Companion can reach future user confirmation when every gate passes`() {
        assertSingleReadyComponent(
            missingComponentId = VENDING_COMPONENT_ID,
            completedComponentId = GMS_COMPONENT_ID,
        )
    }

    @Test
    fun `both missing preserve GmsCore then Companion confirmation order`() {
        val catalog = deviceVerifiedCatalog()
        val plan = simulatedPlan(
            catalog = catalog,
            states = mapOf(
                GMS_COMPONENT_ID to CurrentComponentState.NOT_INSTALLED,
                VENDING_COMPONENT_ID to CurrentComponentState.NOT_INSTALLED,
            ),
        )

        val result = InstallationExecutionGate(catalog).evaluate(
            plan,
            verificationResults(catalog, GMS_COMPONENT_ID, VENDING_COMPONENT_ID),
        )

        assertEquals(InstallationSessionStatus.READY_FOR_USER_CONFIRMATION, result.status)
        assertTrue(result.executionAllowed)
        assertTrue(result.userConfirmationRequired)
        assertEquals(
            listOf(GMS_COMPONENT_ID, VENDING_COMPONENT_ID),
            result.steps.sortedBy { it.order }.map { it.componentId },
        )
        assertTrue(result.steps.all {
            it.state == InstallationStepState.READY_FOR_USER_CONFIRMATION
        })
    }

    @Test
    fun `version conflict fails closed instead of replacing automatically`() {
        val catalog = deviceVerifiedCatalog()
        val plan = simulatedPlan(
            catalog = catalog,
            states = mapOf(
                GMS_COMPONENT_ID to CurrentComponentState.VERSION_MISMATCH,
                VENDING_COMPONENT_ID to CurrentComponentState.OFFICIAL_ARTIFACT_MATCH,
            ),
        )

        val result = InstallationExecutionGate(catalog).evaluate(
            plan,
            verificationResults(catalog, GMS_COMPONENT_ID),
        )

        assertBlocked(result, InstallationBlockReason.VERSION_CONFLICT_REQUIRES_RESOLUTION)
    }

    @Test
    fun `installed signer anomaly fails closed`() {
        val plan = simulatedPlan(
            catalog = builtIn,
            states = mapOf(
                GMS_COMPONENT_ID to CurrentComponentState.SIGNATURE_MISMATCH,
                VENDING_COMPONENT_ID to CurrentComponentState.OFFICIAL_ARTIFACT_MATCH,
            ),
        )

        val result = InstallationExecutionGate(builtIn).evaluate(plan)

        assertBlocked(result, InstallationBlockReason.SIGNATURE_MISMATCH)
    }

    @Test
    fun `current candidate and untested catalog is never executable`() {
        val plan = simulatedPlan(
            catalog = builtIn,
            states = mapOf(
                GMS_COMPONENT_ID to CurrentComponentState.NOT_INSTALLED,
                VENDING_COMPONENT_ID to CurrentComponentState.NOT_INSTALLED,
            ),
        )

        val result = InstallationExecutionGate(builtIn).evaluate(
            plan,
            verificationResults(builtIn, GMS_COMPONENT_ID, VENDING_COMPONENT_ID),
        )

        assertBlocked(result, InstallationBlockReason.COMPATIBILITY_NOT_DEVICE_VERIFIED)
        assertEquals("当前方案尚未完成设备验证，暂不可安装", result.userMessage)
    }

    @Test
    fun `probable and environment verified levels cannot unlock installation`() {
        val catalog = deviceVerifiedCatalog()

        listOf(
            GlobalValidationLevel.PROBABLE,
            GlobalValidationLevel.ENVIRONMENT_VERIFIED,
        ).forEach { validationLevel ->
            val plan = simulatedPlan(
                catalog = catalog,
                states = mapOf(
                    GMS_COMPONENT_ID to CurrentComponentState.NOT_INSTALLED,
                    VENDING_COMPONENT_ID to CurrentComponentState.NOT_INSTALLED,
                ),
                validationLevel = validationLevel,
            )
            val result = InstallationExecutionGate(catalog).evaluate(
                plan,
                verificationResults(catalog, GMS_COMPONENT_ID, VENDING_COMPONENT_ID),
            )

            assertBlocked(result, InstallationBlockReason.COMPATIBILITY_NOT_DEVICE_VERIFIED)
        }
    }

    @Test
    fun `compatibility signature review still exposes device validation gate`() {
        val plan = simulatedPlan(
            catalog = builtIn,
            states = mapOf(
                GMS_COMPONENT_ID to CurrentComponentState.COMPATIBILITY_SIGNATURE_REPORTED,
                VENDING_COMPONENT_ID to CurrentComponentState.COMPATIBILITY_SIGNATURE_REPORTED,
            ),
        )

        val result = InstallationExecutionGate(builtIn).evaluate(plan)

        assertBlocked(result, InstallationBlockReason.CURRENT_ARTIFACT_NOT_VERIFIED)
        assertTrue(
            InstallationBlockReason.COMPATIBILITY_NOT_DEVICE_VERIFIED in result.blockReasons,
        )
        assertEquals("当前方案尚未完成设备验证，暂不可安装", result.userMessage)
    }

    @Test
    fun `HarmonyOS 5 plus is blocked before artifact evaluation`() {
        val plan = simulatedPlan(
            catalog = builtIn,
            states = emptyMap(),
            category = DeviceCategory.HARMONYOS_5_PLUS,
            planId = CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN,
        ).copy(
            status = SimulationPlanStatus.NO_ACTION_REQUIRED,
            nextAction = SimulationNextAction.NO_ACTION_REQUIRED,
        )

        val result = InstallationExecutionGate(builtIn).evaluate(plan)

        assertBlocked(result, InstallationBlockReason.HARMONYOS_5_PLUS_NOT_SUPPORTED)
        assertTrue(result.steps.isEmpty())
    }

    @Test
    fun `missing download evidence fails closed`() {
        val catalog = deviceVerifiedCatalog()
        val plan = simulatedPlan(
            catalog = catalog,
            states = mapOf(
                GMS_COMPONENT_ID to CurrentComponentState.NOT_INSTALLED,
                VENDING_COMPONENT_ID to CurrentComponentState.OFFICIAL_ARTIFACT_MATCH,
            ),
        )

        val result = InstallationExecutionGate(catalog).evaluate(plan)

        assertBlocked(result, InstallationBlockReason.DOWNLOAD_EVIDENCE_MISSING)
    }

    @Test
    fun `official source unavailable fails closed`() {
        val catalog = deviceVerifiedCatalog().let { verified ->
            verified.copy(
                sourceRecords = verified.sourceRecords.map { source ->
                    if (source.componentId == GMS_COMPONENT_ID) {
                        source.copy(
                            availabilityStatus = SourceAvailabilityStatus.MISSING,
                            downloadUrl = null,
                        )
                    } else {
                        source
                    }
                },
            )
        }
        val plan = simulatedPlan(
            catalog = catalog,
            states = mapOf(
                GMS_COMPONENT_ID to CurrentComponentState.NOT_INSTALLED,
                VENDING_COMPONENT_ID to CurrentComponentState.OFFICIAL_ARTIFACT_MATCH,
            ),
        )

        val result = InstallationExecutionGate(catalog).evaluate(plan)

        assertBlocked(result, InstallationBlockReason.OFFICIAL_SOURCE_UNAVAILABLE)
    }

    @Test
    fun `missing audited hash signature and integrity status all fail closed`() {
        val verified = deviceVerifiedCatalog()
        val catalog = verified.copy(
            releases = verified.releases.map { release ->
                release.copy(
                    artifacts = release.artifacts.map { artifact ->
                        if (artifact.componentId == GMS_COMPONENT_ID) {
                            artifact.copy(
                                sha256 = null,
                                signingCertificateDigest = null,
                                integrityStatus = ArtifactIntegrityStatus.SOURCE_VERIFIED,
                            )
                        } else {
                            artifact
                        }
                    },
                )
            },
        )
        val plan = simulatedPlan(
            catalog = catalog,
            states = mapOf(
                GMS_COMPONENT_ID to CurrentComponentState.NOT_INSTALLED,
                VENDING_COMPONENT_ID to CurrentComponentState.OFFICIAL_ARTIFACT_MATCH,
            ),
        )

        val result = InstallationExecutionGate(catalog).evaluate(plan)

        assertBlocked(result, InstallationBlockReason.SHA256_NOT_AUDITED)
        assertTrue(InstallationBlockReason.SIGNATURE_NOT_AUDITED in result.blockReasons)
        assertTrue(
            InstallationBlockReason.ARTIFACT_INTEGRITY_NOT_SIGNATURE_VERIFIED in
                result.blockReasons,
        )
    }

    @Test
    fun `downloaded artifact hash signer package and version must all match`() {
        val catalog = deviceVerifiedCatalog()
        val plan = simulatedPlan(
            catalog = catalog,
            states = mapOf(
                GMS_COMPONENT_ID to CurrentComponentState.NOT_INSTALLED,
                VENDING_COMPONENT_ID to CurrentComponentState.OFFICIAL_ARTIFACT_MATCH,
            ),
        )
        val invalid = exactVerification(catalog, GMS_COMPONENT_ID).copy(
            locallyCalculatedSha256 = RANDOM_SHA256,
            signingCertificateSha256 = listOf(RANDOM_SHA256),
            packageName = "invalid.package",
            versionCode = "1",
            versionName = "invalid",
        )

        val result = InstallationExecutionGate(catalog).evaluate(
            plan,
            mapOf(GMS_COMPONENT_ID to invalid),
        )

        listOf(
            InstallationBlockReason.SHA256_MISMATCH,
            InstallationBlockReason.SIGNATURE_MISMATCH,
            InstallationBlockReason.PACKAGE_NAME_MISMATCH,
            InstallationBlockReason.VERSION_MISMATCH,
        ).forEach { reason -> assertTrue(reason in result.blockReasons) }
        assertFalse(result.executionAllowed)
    }

    private fun assertSingleReadyComponent(
        missingComponentId: String,
        completedComponentId: String,
    ) {
        val catalog = deviceVerifiedCatalog()
        val plan = simulatedPlan(
            catalog = catalog,
            states = mapOf(
                missingComponentId to CurrentComponentState.NOT_INSTALLED,
                completedComponentId to CurrentComponentState.OFFICIAL_ARTIFACT_MATCH,
            ),
        )

        val result = InstallationExecutionGate(catalog).evaluate(
            plan,
            verificationResults(catalog, missingComponentId),
        )

        assertEquals(InstallationSessionStatus.READY_FOR_USER_CONFIRMATION, result.status)
        assertEquals(
            InstallationStepState.READY_FOR_USER_CONFIRMATION,
            result.steps.single { it.componentId == missingComponentId }.state,
        )
        assertEquals(
            InstallationStepState.ALREADY_COMPLETED,
            result.steps.single { it.componentId == completedComponentId }.state,
        )
    }

    private fun assertBlocked(
        result: InstallationSessionPlan,
        reason: InstallationBlockReason,
    ) {
        assertEquals(InstallationSessionStatus.BLOCKED, result.status)
        assertFalse(result.executionAllowed)
        assertFalse(result.userConfirmationRequired)
        assertTrue(reason in result.blockReasons)
    }

    private fun deviceVerifiedCatalog(): ComponentCatalog = builtIn.copy(
        releases = builtIn.releases.map { release ->
            release.copy(
                compatibilityStatus = CompatibilityValidationStatus.DEVICE_VERIFIED,
                artifacts = release.artifacts.map { artifact ->
                    artifact.copy(
                        compatibilityStatus = CompatibilityValidationStatus.DEVICE_VERIFIED,
                    )
                },
            )
        },
    )

    private fun simulatedPlan(
        catalog: ComponentCatalog,
        states: Map<String, CurrentComponentState>,
        category: DeviceCategory = DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT,
        planId: CompatibilityPlanId = CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN,
        validationLevel: GlobalValidationLevel = GlobalValidationLevel.DEVICE_VERIFIED,
    ): SimulatedInstallationPlan {
        val release = catalog.releases.single()
        val selectedArtifacts = if (category == DeviceCategory.HARMONYOS_5_PLUS) {
            emptyList()
        } else {
            release.artifacts.map { artifact ->
                SimulatedArtifact(
                    componentId = artifact.componentId,
                    packageName = artifact.packageName,
                    artifactFilename = requireNotNull(artifact.artifactFilename),
                    versionCode = requireNotNull(artifact.artifactVersionCode),
                    versionName = requireNotNull(artifact.artifactVersionName),
                    sourceType = artifact.sourceType,
                    sourceAvailability = SourceAvailabilityStatus.AVAILABLE,
                    integrityStatus = artifact.integrityStatus,
                    compatibilityStatus = artifact.compatibilityStatus,
                )
            }
        }
        val currentComponents = selectedArtifacts.map { artifact ->
            CurrentComponentDecision(
                componentId = artifact.componentId,
                packageName = artifact.packageName,
                state = states[artifact.componentId] ?: CurrentComponentState.UNKNOWN,
                detail = "test",
            )
        }
        val installationOrder = selectedArtifacts.mapNotNull { artifact ->
            val state = states[artifact.componentId]
            val action = when (state) {
                CurrentComponentState.NOT_INSTALLED -> SimulatedInstallAction.INSTALL
                CurrentComponentState.VERSION_MISMATCH -> SimulatedInstallAction.REPLACE_VERSION
                else -> null
            }
            action?.let {
                SimulatedInstallationStep(
                    order = COMPONENT_ORDER.indexOf(artifact.componentId) + 1,
                    componentId = artifact.componentId,
                    packageName = artifact.packageName,
                    artifactFilename = artifact.artifactFilename,
                    action = it,
                )
            }
        }.sortedBy { it.order }.mapIndexed { index, step -> step.copy(order = index + 1) }
        val hasSignatureMismatch = states.values.any {
            it == CurrentComponentState.SIGNATURE_MISMATCH
        }
        val needsArtifactVerification = states.values.any {
            it == CurrentComponentState.COMPATIBILITY_SIGNATURE_REPORTED
        }
        val allComplete = states.size == COMPONENT_ORDER.size && states.values.all {
            it == CurrentComponentState.OFFICIAL_ARTIFACT_MATCH
        }
        val isHarmony5 = category == DeviceCategory.HARMONYOS_5_PLUS
        return SimulatedInstallationPlan(
            schemaVersion = 1,
            simulationOnly = true,
            realInstallationAllowed = false,
            status = when {
                allComplete -> SimulationPlanStatus.NO_ACTION_REQUIRED
                hasSignatureMismatch || isHarmony5 -> SimulationPlanStatus.BLOCKED
                needsArtifactVerification -> SimulationPlanStatus.REVIEW_REQUIRED
                else -> SimulationPlanStatus.SIMULATION_READY
            },
            deviceCategory = category,
            compatibilityPlanId = planId,
            deviceModel = "test-device",
            systemVersion = if (category == DeviceCategory.HARMONYOS_5_PLUS) "5.0" else "4.2",
            androidApiLevel = 31,
            validationLevel = validationLevel,
            selectedReleaseTag = release.releaseTag,
            selectedArtifacts = selectedArtifacts,
            currentComponents = currentComponents,
            installationOrder = installationOrder,
            nextAction = when {
                allComplete -> SimulationNextAction.NO_ACTION_REQUIRED
                hasSignatureMismatch -> SimulationNextAction.STOP_SIGNATURE_MISMATCH
                isHarmony5 -> SimulationNextAction.STOP_UNSUPPORTED_SYSTEM
                needsArtifactVerification -> SimulationNextAction.VERIFY_CURRENT_ARTIFACT
                else -> SimulationNextAction.REVIEW_SIMULATED_STEPS
            },
            stages = emptyList(),
            warnings = emptyList(),
        )
    }

    private fun verificationResults(
        catalog: ComponentCatalog,
        vararg componentIds: String,
    ): Map<String, ArtifactVerificationResult> = componentIds.associateWith { componentId ->
        exactVerification(catalog, componentId)
    }

    private fun exactVerification(
        catalog: ComponentCatalog,
        componentId: String,
    ): ArtifactVerificationResult {
        val artifact = catalog.releases.single().artifacts.single {
            it.componentId == componentId
        }
        return ArtifactVerificationResult(
            componentId = componentId,
            downloadEvidencePresent = true,
            sourceAssetId = artifact.githubAssetId,
            artifactFilename = artifact.artifactFilename,
            locallyCalculatedSha256 = artifact.sha256,
            signingCertificateSha256 = listOf(requireNotNull(artifact.signingCertificateDigest)),
            apkSignatureVerificationPassed = true,
            packageName = artifact.packageName,
            versionCode = artifact.artifactVersionCode,
            versionName = artifact.artifactVersionName,
        )
    }

    private fun officialPairStates() = mapOf(
        GMS_COMPONENT_ID to CurrentComponentState.OFFICIAL_ARTIFACT_MATCH,
        VENDING_COMPONENT_ID to CurrentComponentState.OFFICIAL_ARTIFACT_MATCH,
    )

    private companion object {
        const val GMS_COMPONENT_ID = "microg_services_huawei_compatible"
        const val VENDING_COMPONENT_ID = "microg_companion_huawei_compatible"
        const val RANDOM_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val COMPONENT_ORDER = listOf(GMS_COMPONENT_ID, VENDING_COMPONENT_ID)
    }
}
