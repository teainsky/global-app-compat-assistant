package com.example.globalcompat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedCompatibilityPlanMatcherTest {
    private val matcher = RuleBasedCompatibilityPlanMatcher()

    @Test
    fun `Pixel package presence alone does not become no action`() {
        val plan = match(
            manufacturer = "Google",
            brand = "google",
            model = "Pixel 9",
            romFamily = RomFamily.UNKNOWN,
            components = components(gms = PRESENT, store = PRESENT),
        )

        assertPlan(plan, DeviceCategory.UNKNOWN, CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN)
        assertEquals(CompatibilityPlanStatus.UNDETERMINED, plan.status)
    }

    @Test
    fun `Samsung package presence alone does not become no action`() {
        val plan = match(
            manufacturer = "samsung",
            brand = "samsung",
            model = "SM-S9280",
            romFamily = RomFamily.ONE_UI,
            romVersion = "60101",
            components = components(gms = PRESENT, store = PRESENT),
        )

        assertPlan(plan, DeviceCategory.UNKNOWN, CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN)
        assertEquals(CompatibilityPlanStatus.UNDETERMINED, plan.status)
    }

    @Test
    fun `Huawei Pura 70 Pro Plus HarmonyOS 4_2 without native GMS uses Huawei plan`() {
        val plan = match(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            model = "HBN-AL10",
            romFamily = RomFamily.HARMONY_OS,
            romVersion = "4.2.0",
            components = components(gms = ABSENT, store = ABSENT, hms = PRESENT),
        )

        assertPlan(
            plan,
            DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT,
            CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN,
        )
        assertEquals(
            listOf(
                "microG Services Huawei-compatible variant",
                "microG Companion Huawei-compatible variant",
            ),
            plan.requiredComponents.map { it.displayName },
        )
        assertEquals(plan.requiredComponents.map { it.componentId }, plan.installationOrder)
        plan.requiredComponents.forEach { component ->
            assertNull(component.versionPolicy.minVersion)
            assertNull(component.versionPolicy.maxVersion)
            assertTrue(component.versionPolicy.blockedVersions.isEmpty())
            assertTrue(component.versionPolicy.verifiedDeviceFamilies.isEmpty())
            assertTrue(component.versionPolicy.verifiedSystemVersions.isEmpty())
        }
    }

    @Test
    fun `Huawei HarmonyOS with one Google component is not treated as complete`() {
        val plan = match(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            model = "HBN-AL10",
            romFamily = RomFamily.HARMONY_OS,
            romVersion = "4.2",
            components = components(gms = PRESENT, store = ABSENT, hms = PRESENT),
        )

        assertPlan(
            plan,
            DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT,
            CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN,
        )
        assertFalse(plan.deviceCategory == DeviceCategory.STANDARD_GMS)
        assertTrue(plan.warnings.any { it.message.contains("不能视为完整环境") })
    }

    @Test
    fun `Xiaomi and OPPO ROMs without complete GMS use China Android category`() {
        val cases = listOf(
            Triple("Xiaomi", "23127PN0CC", RomFamily.HYPER_OS),
            Triple("OPPO", "PHZ110", RomFamily.COLOR_OS),
        )

        cases.forEach { (manufacturer, model, romFamily) ->
            val plan = match(
                manufacturer = manufacturer,
                brand = manufacturer,
                model = model,
                romFamily = romFamily,
                marketVariant = MarketVariant.CHINA_MAINLAND,
                components = components(gms = ABSENT, store = ABSENT),
            )
            assertPlan(
                plan,
                DeviceCategory.CHINA_ANDROID_NO_GMS,
                CompatibilityPlanId.GMS_REPAIR_REQUIRED,
            )
        }
    }

    @Test
    fun `China Android category is not inferred when market variant is unknown`() {
        val plan = match(
            manufacturer = "Xiaomi",
            brand = "Redmi",
            model = "unknown-market-model",
            romFamily = RomFamily.HYPER_OS,
            romVersion = "2.0",
            marketVariant = MarketVariant.UNKNOWN,
            components = components(gms = ABSENT, store = ABSENT),
        )

        assertPlan(plan, DeviceCategory.UNKNOWN, CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN)
        assertEquals(CompatibilityPlanStatus.UNDETERMINED, plan.status)
    }

    @Test
    fun `Play Store without Play Services is partial GMS`() {
        val plan = match(
            manufacturer = "vendor",
            brand = "vendor",
            model = "device",
            romFamily = RomFamily.UNKNOWN,
            components = components(gms = ABSENT, store = PRESENT),
        )

        assertPlan(plan, DeviceCategory.PARTIAL_GMS, CompatibilityPlanId.GMS_REPAIR_REQUIRED)
        assertEquals(listOf("google_play_services"), plan.requiredComponents.map { it.componentId })
    }

    @Test
    fun `HarmonyOS 5_0 5_1 and future versions never enter legacy Huawei plan`() {
        listOf("5.0", "5.1", "6.0", "6.1.0.135").forEach { version ->
            val plan = match(
                manufacturer = "HUAWEI",
                brand = "HUAWEI",
                model = "HarmonyOS-$version-device",
                romFamily = RomFamily.HARMONY_OS,
                romVersion = version,
                components = components(gms = ABSENT, store = ABSENT, hms = PRESENT),
            )

            assertPlan(
                plan,
                DeviceCategory.HARMONYOS_5_PLUS,
                CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN,
            )
            assertTrue(plan.requiredComponents.isEmpty())
            assertTrue(
                plan.warnings.any {
                    it.code == CompatibilityWarningCode.INSTALL_WORKFLOW_NOT_APPLICABLE
                },
            )
        }
    }

    @Test
    fun `unknown HarmonyOS version is not rescued by complete Google packages`() {
        val plan = match(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            model = "unknown-harmony",
            romFamily = RomFamily.HARMONY_VERSION_UNKNOWN,
            romVersion = null,
            components = components(gms = PRESENT, store = PRESENT, hms = PRESENT),
        )

        assertPlan(
            plan,
            DeviceCategory.HARMONY_VERSION_UNKNOWN,
            CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN,
        )
        assertEquals(CompatibilityPlanStatus.UNSUPPORTED, plan.status)
        assertTrue(plan.requiredComponents.isEmpty())
        assertTrue(plan.warnings.single().message.contains("版本无法安全识别"))
    }

    @Test
    fun `unknown ROM stays unknown even when HMS exists`() {
        val plan = match(
            manufacturer = "unknown",
            brand = "unknown",
            model = "unknown",
            romFamily = RomFamily.UNKNOWN,
            components = components(gms = ABSENT, store = ABSENT, hms = PRESENT),
        )

        assertPlan(plan, DeviceCategory.UNKNOWN, CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN)
        assertEquals(CompatibilityPlanStatus.UNDETERMINED, plan.status)
        assertEquals(DetectionConfidence.UNKNOWN, plan.confidence)
    }

    private fun assertPlan(
        plan: CompatibilityPlan,
        category: DeviceCategory,
        planId: CompatibilityPlanId,
    ) {
        assertEquals(category, plan.deviceCategory)
        assertEquals(planId, plan.planId)
        assertTrue("Every decision must retain evidence", plan.evidence.isNotEmpty())
    }

    private fun match(
        manufacturer: String,
        brand: String,
        model: String,
        romFamily: RomFamily,
        romVersion: String? = null,
        marketVariant: MarketVariant = MarketVariant.UNKNOWN,
        components: List<SystemComponent>,
    ): CompatibilityPlan {
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
            apiLevel = 34,
            release = "14",
            securityPatch = "2026-09-01",
            buildDisplay = "test-build",
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
        return matcher.match(
            CompatibilityContext(
                device = device,
                android = android,
                rom = rom,
                components = components,
                deviceProfile = GlobalDeviceProfileClassifier().classify(
                    device = device,
                    android = android,
                    rom = rom,
                    components = components,
                    marketVariant = marketVariant,
                ),
            ),
        )
    }

    private fun components(
        gms: Presence,
        store: Presence,
        hms: Presence = ABSENT,
    ) = listOf(
        component(ComponentId.GOOGLE_PLAY_SERVICES, "com.google.android.gms", gms),
        component(ComponentId.GOOGLE_PLAY_STORE, "com.android.vending", store),
        component(ComponentId.HMS_CORE, "com.huawei.hwid", hms),
    )

    private fun component(
        id: ComponentId,
        packageName: String,
        state: Presence,
    ) = SystemComponent(
        id = id,
        displayName = id.name,
        packageName = packageName,
        presence = if (state == PRESENT) ComponentPresence.PRESENT else ComponentPresence.NOT_INSTALLED,
        enabled = if (state == PRESENT) true else null,
        versionName = if (state == PRESENT) "test-version" else null,
        versionCode = if (state == PRESENT) 1 else null,
    )

    private enum class Presence {
        PRESENT,
        ABSENT,
    }

    private companion object {
        val PRESENT = Presence.PRESENT
        val ABSENT = Presence.ABSENT
    }
}
