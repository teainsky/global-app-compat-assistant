package com.example.globalcompat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeMvpCoveragePolicyTest {
    private val policy = FreeMvpCoveragePolicy()

    @Test
    fun `exact verified HBN profile exposes all three MVP capabilities`() {
        val coverage = policy.evaluate(
            profile(
                manufacturer = "HUAWEI",
                model = "HBN-AL80",
                platformFamily = PlatformFamily.HARMONY_ANDROID_COMPAT,
                romFamily = RomFamily.HARMONY_OS,
                validationLevel = GlobalValidationLevel.DEVICE_VERIFIED,
            ),
            ApplicableWorkflow.HUAWEI_MICROG_COMPAT,
        )

        assertEquals(FreeMvpCapability.entries, coverage.capabilities)
        assertEquals(
            FreeMvpConfigurationStatus.VERIFIED_AVAILABLE,
            coverage.configurationStatus,
        )
    }

    @Test
    fun `known MVP brands are detectable and diagnosable without configuration unlock`() {
        val cases = listOf(
            Triple("HUAWEI", PlatformFamily.HARMONY_ANDROID_COMPAT, RomFamily.HARMONY_OS),
            Triple("HONOR", PlatformFamily.ANDROID_DERIVED, RomFamily.MAGIC_OS),
            Triple("Xiaomi", PlatformFamily.ANDROID_DERIVED, RomFamily.HYPER_OS),
            Triple("Redmi", PlatformFamily.ANDROID_DERIVED, RomFamily.HYPER_OS),
            Triple("POCO", PlatformFamily.ANDROID_DERIVED, RomFamily.HYPER_OS),
            Triple("OPPO", PlatformFamily.ANDROID_DERIVED, RomFamily.COLOR_OS),
            Triple("vivo", PlatformFamily.ANDROID_DERIVED, RomFamily.ORIGIN_OS),
            Triple("iQOO", PlatformFamily.ANDROID_DERIVED, RomFamily.FUNTOUCH_OS),
            Triple("OnePlus", PlatformFamily.ANDROID_DERIVED, RomFamily.OXYGEN_OS),
            Triple("Samsung", PlatformFamily.ANDROID_DERIVED, RomFamily.ONE_UI),
            Triple("Google", PlatformFamily.STANDARD_ANDROID, RomFamily.PIXEL_ANDROID),
            Triple("Motorola", PlatformFamily.STANDARD_ANDROID, RomFamily.MOTOROLA_ANDROID),
            Triple("TECNO", PlatformFamily.ANDROID_DERIVED, RomFamily.TECNO_HIOS),
            Triple("Infinix", PlatformFamily.ANDROID_DERIVED, RomFamily.INFINIX_XOS),
            Triple("itel", PlatformFamily.ANDROID_DERIVED, RomFamily.ITEL_OS),
            Triple("Nothing", PlatformFamily.ANDROID_DERIVED, RomFamily.NOTHING_OS),
        )

        cases.forEach { (manufacturer, platform, rom) ->
            val coverage = policy.evaluate(
                profile(manufacturer, "$manufacturer-test", platform, rom),
                verifiedWorkflow = null,
            )
            assertEquals(BASIC_CAPABILITIES, coverage.capabilities)
            assertEquals(FreeMvpConfigurationStatus.NOT_VERIFIED, coverage.configurationStatus)
        }
    }

    @Test
    fun `probable and environment verified profiles cannot unlock configuration`() {
        listOf(
            GlobalValidationLevel.PROBABLE,
            GlobalValidationLevel.ENVIRONMENT_VERIFIED,
        ).forEach { level ->
            val coverage = policy.evaluate(
                profile(
                    manufacturer = "HUAWEI",
                    model = "unverified-huawei",
                    platformFamily = PlatformFamily.HARMONY_ANDROID_COMPAT,
                    romFamily = RomFamily.HARMONY_OS,
                    validationLevel = level,
                ),
                verifiedWorkflow = ApplicableWorkflow.HUAWEI_MICROG_COMPAT,
            )
            assertFalse(FreeMvpCapability.VERIFIED_CONFIGURATION in coverage.capabilities)
            assertEquals(FreeMvpConfigurationStatus.NOT_VERIFIED, coverage.configurationStatus)
        }
    }

    @Test
    fun `unknown Android stays detectable and diagnosable without unsupported classification`() {
        val profile = profile(
            manufacturer = "NewVendor",
            model = "FirstDevice",
            platformFamily = PlatformFamily.UNKNOWN,
            romFamily = RomFamily.UNKNOWN,
            validationLevel = GlobalValidationLevel.UNKNOWN,
        )
        val coverage = policy.evaluate(profile, verifiedWorkflow = null)
        val decision = GlobalCompatibilityDecisionEngine().decide(profile)

        assertEquals(BASIC_CAPABILITIES, coverage.capabilities)
        assertEquals(FreeMvpConfigurationStatus.NOT_VERIFIED, coverage.configurationStatus)
        assertEquals(CompatibilityDecisionStatus.DIAGNOSTIC_ONLY, decision.decisionStatus)
        assertTrue(decision.blockers.isEmpty())
    }

    @Test
    fun `Harmony native and unknown versions never expose verified configuration`() {
        listOf(
            PlatformFamily.HARMONY_NATIVE to RomFamily.HARMONY_OS_5_PLUS,
            PlatformFamily.HARMONY_VERSION_UNKNOWN to RomFamily.HARMONY_VERSION_UNKNOWN,
        ).forEach { (platform, rom) ->
            val coverage = policy.evaluate(
                profile(
                    manufacturer = "HUAWEI",
                    model = "native-harmony",
                    platformFamily = platform,
                    romFamily = rom,
                    validationLevel = GlobalValidationLevel.DEVICE_VERIFIED,
                ),
                verifiedWorkflow = ApplicableWorkflow.HUAWEI_MICROG_COMPAT,
            )
            assertEquals(BASIC_CAPABILITIES, coverage.capabilities)
            assertEquals(
                FreeMvpConfigurationStatus.WORKFLOW_NOT_APPLICABLE,
                coverage.configurationStatus,
            )
        }
    }

    @Test
    fun `third party compatibility report remains diagnostic only`() {
        val profile = profile(
            manufacturer = "HUAWEI",
            model = "compatibility-runtime",
            platformFamily = PlatformFamily.HARMONY_ANDROID_COMPAT,
            romFamily = RomFamily.HARMONY_OS,
        ).copy(
            googleEnvironmentAssessment = assessment(
                trust = ComponentTrust.COMPATIBILITY_REPORTED,
                health = FunctionalHealth.USER_CONFIRMED,
            ),
        )
        val decision = GlobalCompatibilityDecisionEngine().decide(profile)

        assertEquals(CompatibilityDecisionStatus.DIAGNOSTIC_ONLY, decision.decisionStatus)
        assertEquals(BASIC_CAPABILITIES, decision.freeMvpCoverage.capabilities)
        assertFalse(
            FreeMvpCapability.VERIFIED_CONFIGURATION in
                decision.freeMvpCoverage.capabilities,
        )
    }

    private fun profile(
        manufacturer: String,
        model: String,
        platformFamily: PlatformFamily,
        romFamily: RomFamily,
        validationLevel: GlobalValidationLevel = GlobalValidationLevel.PROBABLE,
    ) = DeviceProfile(
        manufacturer = manufacturer,
        brand = manufacturer,
        model = model,
        deviceFamily = model,
        marketVariant = MarketVariant.UNKNOWN,
        platformFamily = platformFamily,
        runtimeEnvironment = when (platformFamily) {
            PlatformFamily.HARMONY_ANDROID_COMPAT ->
                RuntimeEnvironment.HARMONY_ANDROID_COMPAT
            PlatformFamily.STANDARD_ANDROID,
            PlatformFamily.ANDROID_DERIVED,
            -> RuntimeEnvironment.NATIVE_ANDROID
            else -> RuntimeEnvironment.UNKNOWN
        },
        osFamily = if (platformFamily in HARMONY_PLATFORMS) {
            OsFamily.HARMONY_OS
        } else {
            OsFamily.ANDROID
        },
        osVersion = if (platformFamily in HARMONY_PLATFORMS) "4.2" else "14",
        androidApiLevel = 31,
        romFamily = romFamily,
        romVersion = if (platformFamily in HARMONY_PLATFORMS) "4.2" else "1.0",
        googleEnvironmentAssessment = assessment(),
        installationCapability = when (platformFamily) {
            PlatformFamily.HARMONY_ANDROID_COMPAT ->
                InstallationCapability.LEGACY_HARMONY_COMPATIBLE
            PlatformFamily.HARMONY_NATIVE,
            PlatformFamily.HARMONY_VERSION_UNKNOWN,
            -> InstallationCapability.NOT_APPLICABLE
            else -> InstallationCapability.USER_CONFIRMED_PACKAGE_INSTALL
        },
        validationLevel = validationLevel,
        evidence = emptyList(),
    )

    private fun assessment(
        trust: ComponentTrust = ComponentTrust.UNKNOWN,
        health: FunctionalHealth = FunctionalHealth.UNTESTED,
    ) = GoogleEnvironmentAssessment(
        componentSetState = GoogleComponentSetState.ABSENT,
        componentTrust = trust,
        functionalHealth = health,
        playCertification = PlayCertification.UNKNOWN,
        evidence = emptyList(),
    )

    private companion object {
        val BASIC_CAPABILITIES = listOf(
            FreeMvpCapability.DETECTION,
            FreeMvpCapability.GOOGLE_DIAGNOSTICS,
        )
        val HARMONY_PLATFORMS = setOf(
            PlatformFamily.HARMONY_ANDROID_COMPAT,
            PlatformFamily.HARMONY_NATIVE,
            PlatformFamily.HARMONY_VERSION_UNKNOWN,
        )
    }
}
