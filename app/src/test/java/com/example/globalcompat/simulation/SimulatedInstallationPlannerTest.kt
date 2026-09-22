package com.example.globalcompat.simulation

import com.example.globalcompat.baseline.ComponentFingerprintReadStatus
import com.example.globalcompat.baseline.ComponentMatchContext
import com.example.globalcompat.baseline.InstalledComponentFingerprint
import com.example.globalcompat.baseline.OfficialComponentMatcher
import com.example.globalcompat.baseline.OfficialComponentMatchStatus
import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.CompatibilityValidationStatus
import com.example.globalcompat.catalog.InstalledArtifactSignatureStatus
import com.example.globalcompat.catalog.SourceAvailabilityStatus
import com.example.globalcompat.data.AndroidPlatform
import com.example.globalcompat.data.ApplicableWorkflow
import com.example.globalcompat.data.CompatibilityLayerAssessment
import com.example.globalcompat.data.CompatibilityDecision
import com.example.globalcompat.data.CompatibilityDecisionStatus
import com.example.globalcompat.data.CompatibilityNextAction
import com.example.globalcompat.data.CompatibilityPlan
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.CompatibilityPlanStatus
import com.example.globalcompat.data.DetectionConfidence
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.data.DeviceIdentity
import com.example.globalcompat.data.DeviceProfile
import com.example.globalcompat.data.EnvironmentReport
import com.example.globalcompat.data.GlobalValidationLevel
import com.example.globalcompat.data.GoogleEnvironment
import com.example.globalcompat.data.GoogleCompatibilityLayerStatus
import com.example.globalcompat.data.InstallationCapability
import com.example.globalcompat.data.MarketVariant
import com.example.globalcompat.data.OsFamily
import com.example.globalcompat.data.PlatformFamily
import com.example.globalcompat.data.RomFamily
import com.example.globalcompat.data.RomIdentification
import com.example.globalcompat.installation.InstallationBlockReason
import com.example.globalcompat.installation.InstallationExecutionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedInstallationPlannerTest {
    private val catalog = BuiltInComponentCatalog.catalog
    private val componentMatcher = OfficialComponentMatcher(catalog)
    private val planner = SimulatedInstallationPlanner(catalog)

    @Test
    fun `official pair already installed needs no action`() {
        val result = planner.create(
            harmonyEnvironment(),
            comparisons(officialPair(), OFFICIAL_HASHES),
        )

        assertEquals(SimulationPlanStatus.NO_ACTION_REQUIRED, result.status)
        assertEquals(SimulationNextAction.NO_ACTION_REQUIRED, result.nextAction)
        assertTrue(result.installationOrder.isEmpty())
        assertTrue(result.currentComponents.all {
            it.state == CurrentComponentState.OFFICIAL_ARTIFACT_MATCH
        })
        assertTrue(result.selectedArtifacts.all {
            it.compatibilityStatus == CompatibilityValidationStatus.DEVICE_VERIFIED
        })
        assertEquals(GlobalValidationLevel.DEVICE_VERIFIED, result.validationLevel)
        assertFalse(result.realInstallationAllowed)
    }

    @Test
    fun `both components missing produces complete simulated order`() {
        val result = planner.create(
            harmonyEnvironment(),
            comparisons(listOf(missing(GMS_PACKAGE), missing(VENDING_PACKAGE))),
        )

        assertEquals(SimulationPlanStatus.SIMULATION_READY, result.status)
        assertEquals(SimulationNextAction.REVIEW_SIMULATED_STEPS, result.nextAction)
        assertEquals(
            listOf(GMS_PACKAGE, VENDING_PACKAGE),
            result.installationOrder.map { it.packageName },
        )
        assertTrue(result.installationOrder.all { it.action == SimulatedInstallAction.INSTALL })
        assertEquals(2, result.selectedArtifacts.size)
        assertTrue(result.selectedArtifacts.all {
            it.sourceAvailability == SourceAvailabilityStatus.AVAILABLE
        })
        assertTrue(result.selectedArtifacts.all {
            it.compatibilityStatus == CompatibilityValidationStatus.DEVICE_VERIFIED
        })
        assertEquals(SimulationFlowStage.entries.size, result.stages.size)
    }

    @Test
    fun `one missing component plans only missing artifact`() {
        val result = planner.create(
            harmonyEnvironment(),
            comparisons(
                fingerprints = listOf(official(GMS_PACKAGE), missing(VENDING_PACKAGE)),
                actualHashes = mapOf(GMS_PACKAGE to OFFICIAL_HASHES.getValue(GMS_PACKAGE)),
            ),
        )

        assertEquals(1, result.installationOrder.size)
        assertEquals(VENDING_PACKAGE, result.installationOrder.single().packageName)
        assertEquals(SimulatedInstallAction.INSTALL, result.installationOrder.single().action)
    }

    @Test
    fun `missing GmsCore plans only GmsCore for exact verified profile`() {
        val result = planner.create(
            harmonyEnvironment(),
            comparisons(
                fingerprints = listOf(missing(GMS_PACKAGE), official(VENDING_PACKAGE)),
                actualHashes = mapOf(
                    VENDING_PACKAGE to OFFICIAL_HASHES.getValue(VENDING_PACKAGE),
                ),
            ),
        )

        assertEquals(1, result.installationOrder.size)
        assertEquals(GMS_PACKAGE, result.installationOrder.single().packageName)
        assertEquals(SimulatedInstallAction.INSTALL, result.installationOrder.single().action)
    }

    @Test
    fun `HarmonyOS 4_3 and similar model stay outside verified installation gate`() {
        val nonExactEnvironments = listOf(
            harmonyEnvironment(romVersion = "4.3"),
            harmonyEnvironment(model = "HBN-AL80-SIMILAR"),
        )

        nonExactEnvironments.forEach { environment ->
            val simulated = planner.create(
                environment,
                comparisons(listOf(missing(GMS_PACKAGE), missing(VENDING_PACKAGE))),
            )
            val session = InstallationExecutionGate(catalog).evaluate(simulated)
            assertTrue(
                InstallationBlockReason.COMPATIBILITY_NOT_DEVICE_VERIFIED in session.blockReasons,
            )
        }
    }

    @Test
    fun `version mismatch produces simulated replacement action`() {
        val mismatched = officialPair().map { fingerprint ->
            if (fingerprint.packageName == GMS_PACKAGE) {
                fingerprint.copy(versionCode = requireNotNull(fingerprint.versionCode) - 1)
            } else {
                fingerprint
            }
        }

        val result = planner.create(
            harmonyEnvironment(),
            comparisons(
                fingerprints = mismatched,
                actualHashes = mapOf(VENDING_PACKAGE to OFFICIAL_HASHES.getValue(VENDING_PACKAGE)),
            ),
        )

        assertEquals(1, result.installationOrder.size)
        assertEquals(GMS_PACKAGE, result.installationOrder.single().packageName)
        assertEquals(
            SimulatedInstallAction.REPLACE_VERSION,
            result.installationOrder.single().action,
        )
        assertEquals(
            CurrentComponentState.VERSION_MISMATCH,
            result.currentComponents.single { it.packageName == GMS_PACKAGE }.state,
        )
    }

    @Test
    fun `signer anomaly fails closed without installation order`() {
        val anomalous = officialPair().map { fingerprint ->
            if (fingerprint.packageName == GMS_PACKAGE) {
                fingerprint.copy(reportedSigningCertificateSha256 = listOf(RANDOM_SIGNER))
            } else {
                fingerprint
            }
        }

        val result = planner.create(harmonyEnvironment(), comparisons(anomalous))

        assertEquals(SimulationPlanStatus.BLOCKED, result.status)
        assertEquals(SimulationNextAction.STOP_SIGNATURE_MISMATCH, result.nextAction)
        assertTrue(result.installationOrder.isEmpty())
    }

    @Test
    fun `HarmonyOS 5 plus never enters legacy Harmony plan`() {
        val result = planner.create(harmony5Environment(), emptyList())

        assertEquals(SimulationPlanStatus.BLOCKED, result.status)
        assertEquals(SimulationNextAction.STOP_UNSUPPORTED_SYSTEM, result.nextAction)
        assertTrue(result.selectedArtifacts.isEmpty())
        assertTrue(result.installationOrder.isEmpty())
    }

    @Test
    fun `missing official source availability fails closed`() {
        val unavailableCatalog = catalog.copy(
            sourceRecords = catalog.sourceRecords.map { source ->
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
        val result = SimulatedInstallationPlanner(unavailableCatalog).create(
            harmonyEnvironment(),
            comparisons(listOf(missing(GMS_PACKAGE), missing(VENDING_PACKAGE))),
        )

        assertEquals(SimulationPlanStatus.BLOCKED, result.status)
        assertEquals(
            SimulationNextAction.STOP_OFFICIAL_COMPONENT_UNAVAILABLE,
            result.nextAction,
        )
        assertTrue(result.installationOrder.isEmpty())
    }

    @Test
    fun `compatibility signature requires byte verification and is not mismatch`() {
        val googleReported = officialPair().map { fingerprint ->
            fingerprint.copy(reportedSigningCertificateSha256 = listOf(GOOGLE_SIGNER))
        }

        val result = planner.create(harmonyEnvironment(), comparisons(googleReported))

        assertEquals(SimulationPlanStatus.REVIEW_REQUIRED, result.status)
        assertEquals(SimulationNextAction.VERIFY_CURRENT_ARTIFACT, result.nextAction)
        assertTrue(result.currentComponents.all {
            it.state == CurrentComponentState.COMPATIBILITY_SIGNATURE_REPORTED
        })
        assertTrue(result.installationOrder.isEmpty())
    }

    private fun comparisons(
        fingerprints: List<InstalledComponentFingerprint>,
        actualHashes: Map<String, String> = emptyMap(),
    ) = componentMatcher.compare(
        fingerprints = fingerprints,
        context = ComponentMatchContext(
            manufacturer = "HUAWEI",
            romFamily = RomFamily.HARMONY_OS,
            romVersion = "4.2.0",
        ),
        actualArtifactSha256ByPackage = actualHashes,
    )

    private fun officialPair() = listOf(official(GMS_PACKAGE), official(VENDING_PACKAGE))

    private fun official(packageName: String): InstalledComponentFingerprint {
        val artifact = catalog.releases.single().artifacts.single { it.packageName == packageName }
        return InstalledComponentFingerprint(
            installed = true,
            enabled = true,
            packageName = packageName,
            versionCode = artifact.artifactVersionCode?.toLong(),
            versionName = artifact.artifactVersionName,
            reportedSigningCertificateSha256 = listOf(requireNotNull(artifact.signingCertificateDigest)),
            installSource = "com.huawei.appmarket",
            readStatus = ComponentFingerprintReadStatus.READABLE,
        )
    }

    private fun missing(packageName: String) = InstalledComponentFingerprint(
        installed = false,
        enabled = null,
        packageName = packageName,
        versionCode = null,
        versionName = null,
        reportedSigningCertificateSha256 = emptyList(),
        installSource = null,
        readStatus = ComponentFingerprintReadStatus.NOT_INSTALLED,
    )

    private fun harmonyEnvironment(
        romVersion: String = "4.2.0",
        model: String = "HBN-AL80",
    ) = environment(
        romFamily = RomFamily.HARMONY_OS,
        romVersion = romVersion,
        model = model,
        category = DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT,
        planId = CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN,
        planStatus = CompatibilityPlanStatus.CONFIGURATION_REQUIRED,
    )

    private fun harmony5Environment() = environment(
        romFamily = RomFamily.HARMONY_OS_5_PLUS,
        romVersion = "5.1",
        model = "HBN-AL80",
        category = DeviceCategory.HARMONYOS_5_PLUS,
        planId = CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN,
        planStatus = CompatibilityPlanStatus.UNSUPPORTED,
    )

    private fun environment(
        romFamily: RomFamily,
        romVersion: String,
        model: String,
        category: DeviceCategory,
        planId: CompatibilityPlanId,
        planStatus: CompatibilityPlanStatus,
    ) = EnvironmentReport(
        schemaVersion = 3,
        scannedAtEpochMillis = 1L,
        device = DeviceIdentity(
            brand = "HUAWEI",
            manufacturer = "HUAWEI",
            model = model,
            product = "HBN-AL80",
            device = "HWHBN",
            hardware = "unknown",
            board = "unknown",
            supportedAbis = listOf("arm64-v8a"),
        ),
        android = AndroidPlatform(
            apiLevel = 31,
            release = "12",
            securityPatch = "2026-01-01",
            buildDisplay = "HarmonyOS $romVersion",
            buildIncremental = "test",
            fingerprint = "test",
        ),
        rom = RomIdentification(
            family = romFamily,
            displayName = if (romFamily == RomFamily.HARMONY_OS_5_PLUS) {
                "HarmonyOS 5+"
            } else {
                "HarmonyOS"
            },
            version = romVersion,
            confidence = DetectionConfidence.HIGH,
            evidence = emptyList(),
        ),
        components = emptyList(),
        googleCompatibilityLayer = GoogleCompatibilityLayerStatus(
            assessment = CompatibilityLayerAssessment.NOT_ASSESSED,
            note = "Not assessed",
        ),
        deviceProfile = DeviceProfile(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            model = model,
            deviceFamily = model,
            marketVariant = MarketVariant.UNKNOWN,
            platformFamily = if (romFamily == RomFamily.HARMONY_OS_5_PLUS) {
                PlatformFamily.HARMONY_NATIVE
            } else {
                PlatformFamily.HARMONY_ANDROID_COMPAT
            },
            osFamily = OsFamily.HARMONY_OS,
            osVersion = romVersion,
            androidApiLevel = 31,
            romFamily = romFamily,
            romVersion = romVersion,
            googleEnvironment = GoogleEnvironment.GMS_ABSENT,
            installationCapability = if (romFamily == RomFamily.HARMONY_OS_5_PLUS) {
                InstallationCapability.NOT_APPLICABLE
            } else {
                InstallationCapability.LEGACY_HARMONY_COMPATIBLE
            },
            validationLevel = if (model == "HBN-AL80" && romVersion == "4.2.0") {
                GlobalValidationLevel.DEVICE_VERIFIED
            } else if (romFamily == RomFamily.HARMONY_OS_5_PLUS) {
                GlobalValidationLevel.PROBABLE
            } else {
                GlobalValidationLevel.PROBABLE
            },
            evidence = emptyList(),
        ),
        compatibilityDecision = CompatibilityDecision(
            decisionStatus = when {
                romFamily == RomFamily.HARMONY_OS_5_PLUS ->
                    CompatibilityDecisionStatus.CURRENT_WORKFLOW_NOT_APPLICABLE
                model == "HBN-AL80" && romVersion == "4.2.0" ->
                    CompatibilityDecisionStatus.VERIFIED_WORKFLOW_AVAILABLE
                else -> CompatibilityDecisionStatus.DIAGNOSTIC_ONLY
            },
            validationLevel = if (model == "HBN-AL80" && romVersion == "4.2.0") {
                GlobalValidationLevel.DEVICE_VERIFIED
            } else {
                GlobalValidationLevel.PROBABLE
            },
            googleEnvironment = GoogleEnvironment.GMS_ABSENT,
            applicableWorkflow = if (model == "HBN-AL80" && romVersion == "4.2.0") {
                ApplicableWorkflow.HUAWEI_MICROG_COMPAT
            } else {
                ApplicableWorkflow.NONE
            },
            confidence = DetectionConfidence.HIGH,
            evidence = emptyList(),
            blockers = emptyList(),
            warnings = emptyList(),
            nextAction = if (model == "HBN-AL80" && romVersion == "4.2.0") {
                CompatibilityNextAction.PREPARE_VERIFIED_WORKFLOW
            } else {
                CompatibilityNextAction.RUN_DIAGNOSTICS
            },
        ),
        compatibilityPlan = CompatibilityPlan(
            deviceCategory = category,
            planId = planId,
            status = planStatus,
            requiredComponents = emptyList(),
            installationOrder = listOf(GMS_COMPONENT_ID, VENDING_COMPONENT_ID),
            evidence = emptyList(),
            warnings = emptyList(),
            confidence = DetectionConfidence.HIGH,
        ),
    )

    private companion object {
        const val GMS_COMPONENT_ID = "microg_services_huawei_compatible"
        const val VENDING_COMPONENT_ID = "microg_companion_huawei_compatible"
        const val GMS_PACKAGE = "com.google.android.gms"
        const val VENDING_PACKAGE = "com.android.vending"
        const val GOOGLE_SIGNER =
            "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
        const val RANDOM_SIGNER =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val OFFICIAL_HASHES = BuiltInComponentCatalog.catalog.releases.single().artifacts
            .associate { artifact -> artifact.packageName to requireNotNull(artifact.sha256) }
    }
}
