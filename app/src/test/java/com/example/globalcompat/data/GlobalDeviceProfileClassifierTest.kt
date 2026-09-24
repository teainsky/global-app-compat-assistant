package com.example.globalcompat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalDeviceProfileClassifierTest {
    private val classifier = GlobalDeviceProfileClassifier()

    @Test
    fun `HBN AL80 exact profile remains device verified`() {
        val profile = profile(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            model = "HBN-AL80",
            romFamily = RomFamily.HARMONY_OS,
            romVersion = "4.2.0",
            apiLevel = 31,
            components = googleComponents(present = false),
        )

        assertEquals(PlatformFamily.HARMONY_ANDROID_COMPAT, profile.platformFamily)
        assertEquals(GlobalValidationLevel.DEVICE_VERIFIED, profile.validationLevel)
        assertEquals(MarketVariant.UNKNOWN, profile.marketVariant)
        assertEquals("HBN-AL80", profile.deviceFamily)
        assertEquals(RuntimeEnvironment.HARMONY_ANDROID_COMPAT, profile.runtimeEnvironment)
    }

    @Test
    fun `third party runtime cannot inherit exact HBN device verification`() {
        val profile = profile(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            model = "HBN-AL80",
            romFamily = RomFamily.HARMONY_OS,
            romVersion = "4.2.0",
            apiLevel = 31,
            components = googleComponents(present = true, officialVersions = true),
            runtimeEnvironment = RuntimeEnvironment.THIRD_PARTY_COMPAT_RUNTIME,
        )

        assertEquals(RuntimeEnvironment.THIRD_PARTY_COMPAT_RUNTIME, profile.runtimeEnvironment)
        assertEquals(InstallationCapability.NOT_APPLICABLE, profile.installationCapability)
        assertFalse(profile.validationLevel == GlobalValidationLevel.DEVICE_VERIFIED)
    }

    @Test
    fun `Samsung and Pixel complete GMS are classified independently of brand`() {
        val samsung = profile(
            manufacturer = "samsung",
            brand = "samsung",
            model = "SM-S928B",
            romFamily = RomFamily.ONE_UI,
            components = googleComponents(present = true),
        )
        val pixel = profile(
            manufacturer = "Google",
            brand = "google",
            model = "Pixel 9",
            romFamily = RomFamily.PIXEL_ANDROID,
            components = googleComponents(present = true),
        )

        assertEquals(
            GoogleComponentSetState.COMPLETE,
            samsung.googleEnvironmentAssessment.componentSetState,
        )
        assertEquals(ComponentTrust.UNVERIFIED, samsung.googleEnvironmentAssessment.componentTrust)
        assertEquals(FunctionalHealth.UNTESTED, samsung.googleEnvironmentAssessment.functionalHealth)
        assertEquals(PlayCertification.UNKNOWN, samsung.googleEnvironmentAssessment.playCertification)
        assertEquals(PlatformFamily.ANDROID_DERIVED, samsung.platformFamily)
        assertEquals(
            GoogleComponentSetState.COMPLETE,
            pixel.googleEnvironmentAssessment.componentSetState,
        )
        assertEquals(PlatformFamily.STANDARD_ANDROID, pixel.platformFamily)
    }

    @Test
    fun `same Xiaomi brand allows China absent and global complete GMS states`() {
        val china = profile(
            manufacturer = "Xiaomi",
            brand = "Redmi",
            model = "China-model",
            romFamily = RomFamily.HYPER_OS,
            marketVariant = MarketVariant.CHINA_MAINLAND,
            components = googleComponents(present = false),
        )
        val global = profile(
            manufacturer = "Xiaomi",
            brand = "POCO",
            model = "Global-model",
            romFamily = RomFamily.HYPER_OS,
            marketVariant = MarketVariant.GLOBAL,
            components = googleComponents(present = true),
        )

        assertEquals(
            GoogleComponentSetState.ABSENT,
            china.googleEnvironmentAssessment.componentSetState,
        )
        assertEquals(
            GoogleComponentSetState.COMPLETE,
            global.googleEnvironmentAssessment.componentSetState,
        )
        assertFalse(
            china.googleEnvironmentAssessment.componentSetState ==
                global.googleEnvironmentAssessment.componentSetState,
        )
    }

    @Test
    fun `HBN AL80 official pair receives catalog trust without inferring Play certification`() {
        val profile = profile(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            model = "HBN-AL80",
            romFamily = RomFamily.HARMONY_OS,
            romVersion = "4.2.0",
            apiLevel = 31,
            components = googleComponents(present = true, officialVersions = true),
        )

        assertEquals(
            GoogleComponentSetState.COMPLETE,
            profile.googleEnvironmentAssessment.componentSetState,
        )
        assertEquals(ComponentTrust.TRUSTED, profile.googleEnvironmentAssessment.componentTrust)
        assertEquals(
            FunctionalHealth.USER_CONFIRMED,
            profile.googleEnvironmentAssessment.functionalHealth,
        )
        assertEquals(
            PlayCertification.UNKNOWN,
            profile.googleEnvironmentAssessment.playCertification,
        )
    }

    @Test
    fun `OPPO vivo Honor and Motorola retain distinct ROM taxonomy`() {
        val cases = listOf(
            Triple("OPPO", RomFamily.COLOR_OS, PlatformFamily.ANDROID_DERIVED),
            Triple("vivo", RomFamily.ORIGIN_OS, PlatformFamily.ANDROID_DERIVED),
            Triple("HONOR", RomFamily.MAGIC_OS, PlatformFamily.ANDROID_DERIVED),
            Triple("motorola", RomFamily.MOTOROLA_ANDROID, PlatformFamily.STANDARD_ANDROID),
        )

        cases.forEach { (brand, romFamily, platform) ->
            val profile = profile(
                manufacturer = brand,
                brand = brand,
                model = "$brand-test",
                romFamily = romFamily,
                components = googleComponents(present = true),
            )
            assertEquals(romFamily, profile.romFamily)
            assertEquals(platform, profile.platformFamily)
        }
    }

    @Test
    fun `TECNO Infinix and itel use separate Transsion ROM families`() {
        val cases = listOf(
            "TECNO" to RomFamily.TECNO_HIOS,
            "Infinix" to RomFamily.INFINIX_XOS,
            "itel" to RomFamily.ITEL_OS,
        )

        cases.forEach { (brand, romFamily) ->
            assertEquals(
                romFamily,
                profile(
                    manufacturer = brand,
                    brand = brand,
                    model = "$brand-test",
                    romFamily = romFamily,
                    components = googleComponents(present = false),
                ).romFamily,
            )
        }
    }

    @Test
    fun `unknown Android brand uses unknown taxonomy with generic evidence`() {
        val profile = profile(
            manufacturer = "NewVendor",
            brand = "NewBrand",
            model = "FirstDevice",
            romFamily = RomFamily.UNKNOWN,
            components = googleComponents(present = false),
        )

        assertEquals(PlatformFamily.UNKNOWN, profile.platformFamily)
        assertEquals(GlobalValidationLevel.UNKNOWN, profile.validationLevel)
        assertEquals(MarketVariant.UNKNOWN, profile.marketVariant)
        assertTrue(profile.evidence.any { it.key == "oem.registry" && it.value == "UNKNOWN" })
    }

    @Test
    fun `HarmonyOS 4 and 5 remain separate platform branches`() {
        val harmony4 = profile(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            model = "other-huawei",
            romFamily = RomFamily.HARMONY_OS,
            romVersion = "4.3",
            components = googleComponents(present = false),
        )
        val harmony5 = profile(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            model = "native-harmony",
            romFamily = RomFamily.HARMONY_OS,
            romVersion = "5.0",
            components = googleComponents(present = false),
        )

        assertEquals(PlatformFamily.HARMONY_ANDROID_COMPAT, harmony4.platformFamily)
        assertEquals(PlatformFamily.HARMONY_NATIVE, harmony5.platformFamily)
        assertEquals(GlobalValidationLevel.PROBABLE, harmony5.validationLevel)
        assertEquals(InstallationCapability.NOT_APPLICABLE, harmony5.installationCapability)
    }

    @Test
    fun `unknown HarmonyOS version is not Android compatible`() {
        val unknown = profile(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            model = "unknown-harmony",
            romFamily = RomFamily.HARMONY_VERSION_UNKNOWN,
            romVersion = null,
            components = googleComponents(present = true),
        )

        assertEquals(PlatformFamily.HARMONY_VERSION_UNKNOWN, unknown.platformFamily)
        assertEquals(OsFamily.HARMONY_OS, unknown.osFamily)
        assertEquals(InstallationCapability.NOT_APPLICABLE, unknown.installationCapability)
        assertEquals(GlobalValidationLevel.UNKNOWN, unknown.validationLevel)
        assertEquals(
            GoogleComponentSetState.COMPLETE,
            unknown.googleEnvironmentAssessment.componentSetState,
        )
    }

    @Test
    fun `registry is extensible and initial brands are not a support whitelist`() {
        val registry = OemBrandRegistry()
        val aliases = registry.registrations.flatMap { it.aliases }.map { it.lowercase() }.toSet()

        listOf(
            "samsung", "google", "huawei", "honor", "xiaomi", "redmi", "poco",
            "oppo", "oneplus", "realme", "vivo", "iqoo", "motorola", "lenovo",
            "tecno", "infinix", "itel", "sony", "asus", "nothing", "zte", "nubia",
            "tcl", "hmd", "nokia", "fairphone",
        ).forEach { assertTrue(it in aliases) }
        assertFalse(registry.resolve("NewVendor", "NewBrand").isKnown)
    }

    private fun profile(
        manufacturer: String,
        brand: String,
        model: String,
        romFamily: RomFamily,
        romVersion: String? = "1.0",
        apiLevel: Int = 34,
        marketVariant: MarketVariant = MarketVariant.UNKNOWN,
        components: List<SystemComponent>,
        runtimeEnvironment: RuntimeEnvironment? = null,
    ): DeviceProfile {
        val device = DeviceIdentity(
            brand = brand,
            manufacturer = manufacturer,
            model = model,
            product = model,
            device = model,
            hardware = "test",
            board = "test",
            supportedAbis = listOf("arm64-v8a"),
        )
        val android = AndroidPlatform(
            apiLevel = apiLevel,
            release = "14",
            securityPatch = null,
            buildDisplay = "test",
            buildIncremental = "1",
            fingerprint = "test/fingerprint",
        )
        val rom = RomIdentification(
            family = romFamily,
            displayName = romFamily.name,
            version = romVersion,
            confidence = if (romFamily == RomFamily.UNKNOWN) {
                DetectionConfidence.UNKNOWN
            } else {
                DetectionConfidence.HIGH
            },
            evidence = listOf(DetectionEvidence("test.rom", romFamily.name)),
        )
        return classifier.classify(
            device = device,
            android = android,
            rom = rom,
            components = components,
            marketVariant = marketVariant,
            runtimeEnvironment = RuntimeEnvironmentDetection(
                environment = runtimeEnvironment ?: when {
                    romFamily == RomFamily.HARMONY_OS ->
                        RuntimeEnvironment.HARMONY_ANDROID_COMPAT
                    romFamily == RomFamily.HARMONY_OS_5_PLUS ||
                        romFamily == RomFamily.HARMONY_VERSION_UNKNOWN ->
                        RuntimeEnvironment.UNKNOWN
                    else -> RuntimeEnvironment.NATIVE_ANDROID
                },
                evidence = emptyList(),
            ),
        )
    }

    private fun googleComponents(
        present: Boolean,
        officialVersions: Boolean = false,
    ): List<SystemComponent> = listOf(
        component(ComponentId.GOOGLE_PLAY_SERVICES, present, officialVersions),
        component(ComponentId.GOOGLE_PLAY_STORE, present, officialVersions),
    )

    private fun component(
        id: ComponentId,
        present: Boolean,
        officialVersions: Boolean,
    ) = SystemComponent(
        id = id,
        displayName = id.name,
        packageName = if (id == ComponentId.GOOGLE_PLAY_SERVICES) {
            "com.google.android.gms"
        } else {
            "com.android.vending"
        },
        presence = if (present) ComponentPresence.PRESENT else ComponentPresence.NOT_INSTALLED,
        enabled = if (present) true else null,
        versionName = null,
        versionCode = if (present && officialVersions) {
            when (id) {
                ComponentId.GOOGLE_PLAY_SERVICES -> 252432032L
                ComponentId.GOOGLE_PLAY_STORE -> 84022632L
                ComponentId.HMS_CORE -> null
            }
        } else {
            null
        },
    )
}
