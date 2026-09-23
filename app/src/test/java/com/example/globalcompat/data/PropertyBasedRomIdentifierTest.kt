package com.example.globalcompat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PropertyBasedRomIdentifierTest {
    private val identifier = PropertyBasedRomIdentifier()

    @Test
    fun `identifies Huawei Pura 70 Pro Plus HarmonyOS 4_2 target`() {
        val result = identify(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            display = "HBN-AL10 4.2.0.130",
            properties = mapOf("hw_sc.build.platform.version" to "4.2.0"),
        )

        assertEquals(RomFamily.HARMONY_OS, result.family)
        assertEquals("4.2.0", result.version)
        assertEquals(DetectionConfidence.HIGH, result.confidence)
    }

    @Test
    fun `supports reserved ROM property contracts`() {
        val cases = listOf(
            "ro.mi.os.version.name" to RomFamily.HYPER_OS,
            "ro.build.version.opporom" to RomFamily.COLOR_OS,
            "ro.build.version.magic" to RomFamily.MAGIC_OS,
            "ro.build.version.oneui" to RomFamily.ONE_UI,
        )

        cases.forEach { (property, expectedFamily) ->
            assertEquals(
                expectedFamily,
                identify(properties = mapOf(property to "test-version")).family,
            )
        }
    }

    @Test
    fun `identifies OriginOS only when vivo name explicitly says Origin`() {
        val result = identify(
            properties = mapOf(
                "ro.vivo.os.name" to "OriginOS",
                "ro.vivo.os.version" to "4",
            ),
        )

        assertEquals(RomFamily.ORIGIN_OS, result.family)
        assertEquals("4", result.version)
    }

    @Test
    fun `identifies extended global ROM property contracts`() {
        val cases = listOf(
            "ro.oxygen.version" to RomFamily.OXYGEN_OS,
            "ro.build.version.realmeui" to RomFamily.REALME_UI,
            "ro.build.version.myos" to RomFamily.MY_OS,
            "ro.build.version.nothing" to RomFamily.NOTHING_OS,
            "ro.transsion.hios.version" to RomFamily.TECNO_HIOS,
            "ro.transsion.xos.version" to RomFamily.INFINIX_XOS,
            "ro.transsion.itelos.version" to RomFamily.ITEL_OS,
            "ro.tcl.ui.version" to RomFamily.TCL_UI,
        )

        cases.forEach { (property, expectedFamily) ->
            assertEquals(
                expectedFamily,
                identify(properties = mapOf(property to "test-version")).family,
            )
        }
    }

    @Test
    fun `identifies Funtouch separately from OriginOS`() {
        val result = identify(
            manufacturer = "vivo",
            brand = "vivo",
            properties = mapOf(
                "ro.vivo.os.name" to "Funtouch OS",
                "ro.vivo.os.version" to "15",
            ),
        )

        assertEquals(RomFamily.FUNTOUCH_OS, result.family)
        assertEquals("15", result.version)
    }

    @Test
    fun `known global OEM fallback remains only medium confidence evidence`() {
        val cases = listOf(
            Triple("samsung", "samsung", RomFamily.ONE_UI),
            Triple("Google", "google", RomFamily.PIXEL_ANDROID),
            Triple("motorola", "motorola", RomFamily.MOTOROLA_ANDROID),
            Triple("TECNO", "TECNO", RomFamily.TECNO_HIOS),
            Triple("Infinix", "Infinix", RomFamily.INFINIX_XOS),
            Triple("itel", "itel", RomFamily.ITEL_OS),
        )

        cases.forEach { (manufacturer, brand, expectedFamily) ->
            val result = identify(manufacturer = manufacturer, brand = brand)
            assertEquals(expectedFamily, result.family)
            assertEquals(DetectionConfidence.MEDIUM, result.confidence)
        }
    }

    @Test
    fun `does not infer HarmonyOS from Huawei brand alone`() {
        val result = identify(manufacturer = "HUAWEI", brand = "HUAWEI")

        assertEquals(RomFamily.UNKNOWN, result.family)
        assertNull(result.version)
        assertEquals(DetectionConfidence.UNKNOWN, result.confidence)
    }

    @Test
    fun `identifies HarmonyOS 5_0 5_1 and future major versions as 5 plus`() {
        listOf("5.0", "5.1", "6.0", "6.1.0.135").forEach { version ->
            val result = identify(
                manufacturer = "HUAWEI",
                brand = "HUAWEI",
                display = "HarmonyOS $version",
                properties = mapOf("hw_sc.build.platform.version" to version),
            )

            assertEquals(RomFamily.HARMONY_OS_5_PLUS, result.family)
            assertEquals(version, result.version)
        }
    }

    @Test
    fun `empty HarmonyOS version is explicitly unknown`() {
        val result = identify(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            display = "HarmonyOS",
            properties = mapOf("hw_sc.build.platform.version" to ""),
        )

        assertEquals(RomFamily.HARMONY_VERSION_UNKNOWN, result.family)
        assertNull(result.version)
        assertEquals(DetectionConfidence.UNKNOWN, result.confidence)
    }

    @Test
    fun `unparseable HarmonyOS version is explicitly unknown`() {
        val result = identify(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            properties = mapOf("ro.build.version.harmony" to "not-a-version"),
        )

        assertEquals(RomFamily.HARMONY_VERSION_UNKNOWN, result.family)
        assertNull(result.version)
    }

    @Test
    fun `conflicting HarmonyOS major properties are explicitly unknown`() {
        val result = identify(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            properties = mapOf(
                "hw_sc.build.platform.version" to "4.2.0",
                "ro.build.version.harmony" to "6.1.0.135",
            ),
        )

        assertEquals(RomFamily.HARMONY_VERSION_UNKNOWN, result.family)
        assertNull(result.version)
    }

    @Test
    fun `HarmonyOS display without a confirmable major is explicitly unknown`() {
        val result = identify(
            manufacturer = "HUAWEI",
            brand = "HUAWEI",
            display = "HarmonyOS",
        )

        assertEquals(RomFamily.HARMONY_VERSION_UNKNOWN, result.family)
        assertNull(result.version)
    }

    private fun identify(
        manufacturer: String = "vendor",
        brand: String = "brand",
        display: String = "build",
        properties: Map<String, String> = emptyMap(),
    ) = identifier.identify(
        RomProbe(
            manufacturer = manufacturer,
            brand = brand,
            buildDisplay = display,
            properties = properties,
        ),
    )
}
