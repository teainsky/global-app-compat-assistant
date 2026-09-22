package com.example.globalcompat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalCompatibilityDecisionEngineTest {
    private val engine = GlobalCompatibilityDecisionEngine()

    @Test
    fun `HBN AL80 with complete Google environment needs no action`() {
        val decision = engine.decide(hbnProfile(GoogleEnvironment.GMS_COMPLETE))

        assertEquals(CompatibilityDecisionStatus.NO_ACTION_REQUIRED, decision.decisionStatus)
        assertEquals(GlobalValidationLevel.DEVICE_VERIFIED, decision.validationLevel)
        assertEquals(ApplicableWorkflow.NONE, decision.applicableWorkflow)
        assertEquals(CompatibilityNextAction.KEEP_CURRENT_ENVIRONMENT, decision.nextAction)
    }

    @Test
    fun `HBN AL80 with both components absent has verified signed workflow`() {
        val decision = engine.decide(hbnProfile(GoogleEnvironment.GMS_ABSENT))

        assertEquals(
            CompatibilityDecisionStatus.VERIFIED_WORKFLOW_AVAILABLE,
            decision.decisionStatus,
        )
        assertEquals(ApplicableWorkflow.HUAWEI_MICROG_COMPAT, decision.applicableWorkflow)
        assertEquals(CompatibilityNextAction.PREPARE_VERIFIED_WORKFLOW, decision.nextAction)
        assertTrue(decision.blockers.isEmpty())
    }

    @Test
    fun `complete GMS is no action across Pixel Samsung and Xiaomi Global`() {
        listOf(
            profile("Google", "Pixel 9", RomFamily.PIXEL_ANDROID, MarketVariant.GLOBAL),
            profile("Samsung", "SM-S928B", RomFamily.ONE_UI, MarketVariant.GLOBAL),
            profile("Xiaomi", "2407FPN8EG", RomFamily.HYPER_OS, MarketVariant.GLOBAL),
        ).forEach { profile ->
            val decision = engine.decide(profile.copy(googleEnvironment = GoogleEnvironment.GMS_COMPLETE))
            assertEquals(
                CompatibilityDecisionStatus.NO_ACTION_REQUIRED,
                decision.decisionStatus,
            )
        }
    }

    @Test
    fun `Xiaomi China absent GMS is diagnostic only without exact verification`() {
        val decision = engine.decide(
            profile(
                manufacturer = "Xiaomi",
                model = "China-model",
                romFamily = RomFamily.HYPER_OS,
                marketVariant = MarketVariant.CHINA_MAINLAND,
            ).copy(googleEnvironment = GoogleEnvironment.GMS_ABSENT),
        )

        assertDiagnosticOnly(decision)
    }

    @Test
    fun `OPPO vivo and Honor unverified environments remain diagnostic only`() {
        listOf(
            profile("OPPO", "OPPO-test", RomFamily.COLOR_OS),
            profile("vivo", "vivo-test", RomFamily.ORIGIN_OS),
            profile("HONOR", "HONOR-test", RomFamily.MAGIC_OS),
        ).forEach { profile ->
            assertDiagnosticOnly(
                engine.decide(profile.copy(googleEnvironment = GoogleEnvironment.GMS_PARTIAL)),
            )
        }
    }

    @Test
    fun `Motorola and Transsion absent environments remain diagnostic only`() {
        listOf(
            profile("motorola", "moto-test", RomFamily.MOTOROLA_ANDROID),
            profile("TECNO", "TECNO-test", RomFamily.TECNO_HIOS),
            profile("Infinix", "Infinix-test", RomFamily.INFINIX_XOS),
            profile("itel", "itel-test", RomFamily.ITEL_OS),
        ).forEach { profile ->
            assertDiagnosticOnly(
                engine.decide(profile.copy(googleEnvironment = GoogleEnvironment.GMS_ABSENT)),
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
                googleEnvironment = GoogleEnvironment.UNKNOWN,
            ),
        )

        assertEquals(CompatibilityDecisionStatus.UNKNOWN, decision.decisionStatus)
        assertTrue(decision.blockers.isEmpty())
        assertEquals(ApplicableWorkflow.NONE, decision.applicableWorkflow)
    }

    @Test
    fun `unknown Android OEM with absent GMS can receive diagnostics only`() {
        val decision = engine.decide(
            profile(
                manufacturer = "NewVendor",
                model = "FirstDevice",
                romFamily = RomFamily.UNKNOWN,
                validationLevel = GlobalValidationLevel.UNKNOWN,
            ).copy(
                platformFamily = PlatformFamily.UNKNOWN,
                googleEnvironment = GoogleEnvironment.GMS_ABSENT,
            ),
        )

        assertDiagnosticOnly(decision)
    }

    @Test
    fun `unverified HarmonyOS 4 profile is diagnostic only`() {
        val decision = engine.decide(
            hbnProfile(GoogleEnvironment.GMS_ABSENT).copy(
                model = "OTHER-HUAWEI",
                deviceFamily = "OTHER-HUAWEI",
                validationLevel = GlobalValidationLevel.PROBABLE,
            ),
        )

        assertDiagnosticOnly(decision)
    }

    @Test
    fun `HarmonyOS 5 and 6 reject only the legacy workflow`() {
        listOf("5.0", "6.0").forEach { version ->
            val decision = engine.decide(
                hbnProfile(GoogleEnvironment.GMS_ABSENT).copy(
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
            assertTrue(decision.blockers.isEmpty())
        }
    }

    @Test
    fun `probable and environment verified never unlock an exact signed workflow`() {
        listOf(
            GlobalValidationLevel.PROBABLE,
            GlobalValidationLevel.ENVIRONMENT_VERIFIED,
        ).forEach { level ->
            val decision = engine.decide(
                hbnProfile(GoogleEnvironment.GMS_ABSENT).copy(validationLevel = level),
            )
            assertDiagnosticOnly(decision)
        }
    }

    @Test
    fun `same brand receives different decisions from different Google states`() {
        val base = profile("Xiaomi", "same-model", RomFamily.HYPER_OS)
        val complete = engine.decide(base.copy(googleEnvironment = GoogleEnvironment.GMS_COMPLETE))
        val absent = engine.decide(base.copy(googleEnvironment = GoogleEnvironment.GMS_ABSENT))

        assertEquals(CompatibilityDecisionStatus.NO_ACTION_REQUIRED, complete.decisionStatus)
        assertEquals(CompatibilityDecisionStatus.DIAGNOSTIC_ONLY, absent.decisionStatus)
    }

    @Test
    fun `unknown market stays unknown and is recorded without inference`() {
        val decision = engine.decide(
            profile("Xiaomi", "unknown-market", RomFamily.HYPER_OS).copy(
                marketVariant = MarketVariant.UNKNOWN,
                googleEnvironment = GoogleEnvironment.GMS_ABSENT,
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
                googleEnvironment = GoogleEnvironment.GMS_ABSENT,
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

    private fun hbnProfile(googleEnvironment: GoogleEnvironment) = DeviceProfile(
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
        googleEnvironment = googleEnvironment,
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
        googleEnvironment = GoogleEnvironment.UNKNOWN,
        installationCapability = InstallationCapability.USER_CONFIRMED_PACKAGE_INSTALL,
        validationLevel = validationLevel,
        evidence = listOf(
            DetectionEvidence("market.variant", marketVariant.name),
            DetectionEvidence("validation.level", validationLevel.name),
        ),
    )
}
