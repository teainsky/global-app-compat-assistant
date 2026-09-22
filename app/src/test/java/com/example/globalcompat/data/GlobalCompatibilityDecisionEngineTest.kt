package com.example.globalcompat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalCompatibilityDecisionEngineTest {
    private val engine = GlobalCompatibilityDecisionEngine()

    @Test
    fun `HBN AL80 trusted user-confirmed pair needs no action without Play claim`() {
        val decision = engine.decide(
            hbnProfile(
                assessment(
                    GoogleComponentSetState.COMPLETE,
                    ComponentTrust.TRUSTED,
                    FunctionalHealth.USER_CONFIRMED,
                ),
            ),
        )

        assertEquals(CompatibilityDecisionStatus.NO_ACTION_REQUIRED, decision.decisionStatus)
        assertEquals(GlobalValidationLevel.DEVICE_VERIFIED, decision.validationLevel)
        assertEquals(ApplicableWorkflow.NONE, decision.applicableWorkflow)
        assertEquals(PlayCertification.UNKNOWN, decision.googleEnvironmentAssessment.playCertification)
        assertTrue(decision.warnings.any { it.code == "PLAY_CERTIFICATION_UNKNOWN" })
    }

    @Test
    fun `HBN AL80 with both components absent has verified signed workflow`() {
        val decision = engine.decide(hbnProfile(absentAssessment()))

        assertEquals(
            CompatibilityDecisionStatus.VERIFIED_WORKFLOW_AVAILABLE,
            decision.decisionStatus,
        )
        assertEquals(ApplicableWorkflow.HUAWEI_MICROG_COMPAT, decision.applicableWorkflow)
        assertEquals(CompatibilityNextAction.PREPARE_VERIFIED_WORKFLOW, decision.nextAction)
        assertTrue(decision.blockers.isEmpty())
    }

    @Test
    fun `package presence alone on Pixel Samsung and Xiaomi Global is diagnostic only`() {
        listOf(
            profile("Google", "Pixel 9", RomFamily.PIXEL_ANDROID, MarketVariant.GLOBAL),
            profile("Samsung", "SM-S928B", RomFamily.ONE_UI, MarketVariant.GLOBAL),
            profile("Xiaomi", "2407FPN8EG", RomFamily.HYPER_OS, MarketVariant.GLOBAL),
        ).forEach { profile ->
            val decision = engine.decide(profile.copy(googleEnvironmentAssessment = presenceOnly()))
            assertDiagnosticOnly(decision)
            assertNotEquals(
                CompatibilityDecisionStatus.NO_ACTION_REQUIRED,
                decision.decisionStatus,
            )
        }
    }

    @Test
    fun `trusted verified healthy environment can need no action without Play inference`() {
        val decision = engine.decide(
            profile("Google", "Pixel-lab", RomFamily.PIXEL_ANDROID).copy(
                validationLevel = GlobalValidationLevel.ENVIRONMENT_VERIFIED,
                googleEnvironmentAssessment = assessment(
                    GoogleComponentSetState.COMPLETE,
                    ComponentTrust.TRUSTED,
                    FunctionalHealth.VERIFIED_HEALTHY,
                ),
            ),
        )

        assertEquals(CompatibilityDecisionStatus.NO_ACTION_REQUIRED, decision.decisionStatus)
        assertEquals(PlayCertification.UNKNOWN, decision.googleEnvironmentAssessment.playCertification)
    }

    @Test
    fun `user confirmation alone cannot produce no action without exact device verification`() {
        val decision = engine.decide(
            profile("Samsung", "SM-test", RomFamily.ONE_UI).copy(
                googleEnvironmentAssessment = assessment(
                    GoogleComponentSetState.COMPLETE,
                    ComponentTrust.TRUSTED,
                    FunctionalHealth.USER_CONFIRMED,
                ),
            ),
        )

        assertDiagnosticOnly(decision)
    }

    @Test
    fun `compatibility reported is not trusted`() {
        val decision = engine.decide(
            profile("Vendor", "compat-test", RomFamily.AOSP).copy(
                googleEnvironmentAssessment = assessment(
                    GoogleComponentSetState.COMPLETE,
                    ComponentTrust.COMPATIBILITY_REPORTED,
                    FunctionalHealth.USER_CONFIRMED,
                ),
            ),
        )

        assertDiagnosticOnly(decision)
    }

    @Test
    fun `Xiaomi China absent GMS is diagnostic only without exact verification`() {
        val decision = engine.decide(
            profile(
                manufacturer = "Xiaomi",
                model = "China-model",
                romFamily = RomFamily.HYPER_OS,
                marketVariant = MarketVariant.CHINA_MAINLAND,
            ).copy(googleEnvironmentAssessment = absentAssessment()),
        )

        assertDiagnosticOnly(decision)
    }

    @Test
    fun `OPPO vivo and Honor unverified partial environments remain diagnostic only`() {
        listOf(
            profile("OPPO", "OPPO-test", RomFamily.COLOR_OS),
            profile("vivo", "vivo-test", RomFamily.ORIGIN_OS),
            profile("HONOR", "HONOR-test", RomFamily.MAGIC_OS),
        ).forEach { profile ->
            assertDiagnosticOnly(
                engine.decide(
                    profile.copy(
                        googleEnvironmentAssessment = assessment(
                            GoogleComponentSetState.PARTIAL,
                            ComponentTrust.UNVERIFIED,
                            FunctionalHealth.UNTESTED,
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `unknown Android OEM uses generic fallback and is never blocked`() {
        val decision = engine.decide(
            profile(
                manufacturer = "NewVendor",
                model = "FirstDevice",
                romFamily = RomFamily.UNKNOWN,
                validationLevel = GlobalValidationLevel.UNKNOWN,
            ).copy(
                platformFamily = PlatformFamily.UNKNOWN,
                googleEnvironmentAssessment = unknownAssessment(),
            ),
        )

        assertEquals(CompatibilityDecisionStatus.UNKNOWN, decision.decisionStatus)
        assertTrue(decision.blockers.isEmpty())
    }

    @Test
    fun `unknown Android OEM with absent components can receive diagnostics only`() {
        val decision = engine.decide(
            profile(
                manufacturer = "NewVendor",
                model = "FirstDevice",
                romFamily = RomFamily.UNKNOWN,
                validationLevel = GlobalValidationLevel.UNKNOWN,
            ).copy(
                platformFamily = PlatformFamily.UNKNOWN,
                googleEnvironmentAssessment = absentAssessment(),
            ),
        )

        assertDiagnosticOnly(decision)
    }

    @Test
    fun `unverified HarmonyOS 4 profile is diagnostic only`() {
        val decision = engine.decide(
            hbnProfile(absentAssessment()).copy(
                model = "OTHER-HUAWEI",
                deviceFamily = "OTHER-HUAWEI",
                validationLevel = GlobalValidationLevel.PROBABLE,
            ),
        )

        assertDiagnosticOnly(decision)
    }

    @Test
    fun `HarmonyOS 5 and 6 native branch wins over component presence`() {
        listOf("5.0", "6.0").forEach { version ->
            val decision = engine.decide(
                hbnProfile(
                    assessment(
                        GoogleComponentSetState.COMPLETE,
                        ComponentTrust.TRUSTED,
                        FunctionalHealth.VERIFIED_HEALTHY,
                    ),
                ).copy(
                    model = "native-$version",
                    deviceFamily = "native-$version",
                    platformFamily = PlatformFamily.HARMONY_NATIVE,
                    osVersion = version,
                    romFamily = RomFamily.HARMONY_OS_5_PLUS,
                    romVersion = version,
                    installationCapability = InstallationCapability.NOT_APPLICABLE,
                    validationLevel = GlobalValidationLevel.PROBABLE,
                ),
            )

            assertEquals(
                CompatibilityDecisionStatus.CURRENT_WORKFLOW_NOT_APPLICABLE,
                decision.decisionStatus,
            )
            assertEquals(ApplicableWorkflow.NONE, decision.applicableWorkflow)
        }
    }

    @Test
    fun `probable and environment verified never unlock an exact signed workflow`() {
        listOf(
            GlobalValidationLevel.PROBABLE,
            GlobalValidationLevel.ENVIRONMENT_VERIFIED,
        ).forEach { level ->
            val decision = engine.decide(
                hbnProfile(absentAssessment()).copy(validationLevel = level),
            )
            assertDiagnosticOnly(decision)
        }
    }

    @Test
    fun `unknown market stays unknown and is recorded without inference`() {
        val decision = engine.decide(
            profile("Xiaomi", "unknown-market", RomFamily.HYPER_OS).copy(
                marketVariant = MarketVariant.UNKNOWN,
                googleEnvironmentAssessment = absentAssessment(),
            ),
        )

        assertEquals(CompatibilityDecisionStatus.DIAGNOSTIC_ONLY, decision.decisionStatus)
        assertTrue(decision.evidence.any {
            it.code == "market.variant" && it.observedValue == MarketVariant.UNKNOWN.name
        })
    }

    @Test
    fun `explicit trusted blocked validation becomes known rule block`() {
        val decision = engine.decide(
            profile("KnownVendor", "blocked-device", RomFamily.AOSP).copy(
                validationLevel = GlobalValidationLevel.BLOCKED,
                googleEnvironmentAssessment = absentAssessment(),
            ),
        )

        assertEquals(CompatibilityDecisionStatus.BLOCKED_BY_KNOWN_RULE, decision.decisionStatus)
        assertTrue(decision.blockers.isNotEmpty())
    }

    private fun assertDiagnosticOnly(decision: CompatibilityDecision) {
        assertEquals(CompatibilityDecisionStatus.DIAGNOSTIC_ONLY, decision.decisionStatus)
        assertEquals(ApplicableWorkflow.NONE, decision.applicableWorkflow)
        assertEquals(CompatibilityNextAction.RUN_DIAGNOSTICS, decision.nextAction)
    }

    private fun hbnProfile(assessment: GoogleEnvironmentAssessment) = DeviceProfile(
        manufacturer = "HUAWEI",
        brand = "HUAWEI",
        model = "HBN-AL80",
        deviceFamily = "HBN-AL80",
        marketVariant = MarketVariant.UNKNOWN,
        platformFamily = PlatformFamily.HARMONY_ANDROID_COMPAT,
        osFamily = OsFamily.HARMONY_OS,
        osVersion = "4.2.0",
        androidApiLevel = 31,
        romFamily = RomFamily.HARMONY_OS,
        romVersion = "4.2.0",
        googleEnvironmentAssessment = assessment,
        installationCapability = InstallationCapability.LEGACY_HARMONY_COMPATIBLE,
        validationLevel = GlobalValidationLevel.DEVICE_VERIFIED,
        evidence = listOf(
            DetectionEvidence("market.variant", MarketVariant.UNKNOWN.name),
            DetectionEvidence("validation.level", GlobalValidationLevel.DEVICE_VERIFIED.name),
        ),
    )

    private fun profile(
        manufacturer: String,
        model: String,
        romFamily: RomFamily,
        marketVariant: MarketVariant = MarketVariant.UNKNOWN,
        validationLevel: GlobalValidationLevel = GlobalValidationLevel.PROBABLE,
    ) = DeviceProfile(
        manufacturer = manufacturer,
        brand = manufacturer,
        model = model,
        deviceFamily = model,
        marketVariant = marketVariant,
        platformFamily = when (romFamily) {
            RomFamily.AOSP,
            RomFamily.PIXEL_ANDROID,
            RomFamily.MOTOROLA_ANDROID,
            -> PlatformFamily.STANDARD_ANDROID
            RomFamily.UNKNOWN -> PlatformFamily.UNKNOWN
            else -> PlatformFamily.ANDROID_DERIVED
        },
        osFamily = OsFamily.ANDROID,
        osVersion = "14",
        androidApiLevel = 34,
        romFamily = romFamily,
        romVersion = "1.0",
        googleEnvironmentAssessment = unknownAssessment(),
        installationCapability = InstallationCapability.USER_CONFIRMED_PACKAGE_INSTALL,
        validationLevel = validationLevel,
        evidence = listOf(
            DetectionEvidence("market.variant", marketVariant.name),
            DetectionEvidence("validation.level", validationLevel.name),
        ),
    )

    private fun presenceOnly() = assessment(
        GoogleComponentSetState.COMPLETE,
        ComponentTrust.UNVERIFIED,
        FunctionalHealth.UNTESTED,
    )

    private fun absentAssessment() = assessment(
        GoogleComponentSetState.ABSENT,
        ComponentTrust.UNKNOWN,
        FunctionalHealth.UNTESTED,
    )

    private fun unknownAssessment() = assessment(
        GoogleComponentSetState.UNKNOWN,
        ComponentTrust.UNKNOWN,
        FunctionalHealth.UNKNOWN,
    )

    private fun assessment(
        componentSetState: GoogleComponentSetState,
        componentTrust: ComponentTrust,
        functionalHealth: FunctionalHealth,
        playCertification: PlayCertification = PlayCertification.UNKNOWN,
    ) = GoogleEnvironmentAssessment(
        componentSetState = componentSetState,
        componentTrust = componentTrust,
        functionalHealth = functionalHealth,
        playCertification = playCertification,
        evidence = emptyList(),
    )
}
