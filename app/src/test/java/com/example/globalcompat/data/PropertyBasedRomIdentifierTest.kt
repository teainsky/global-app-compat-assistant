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
    fun `does not infer HarmonyOS from Huawei brand alone`() {
        val result = identify(manufacturer = "HUAWEI", brand = "HUAWEI")

        assertEquals(RomFamily.UNKNOWN, result.family)
        assertNull(result.version)
        assertEquals(DetectionConfidence.UNKNOWN, result.confidence)
    }

    @Test
    fun `identifies HarmonyOS 5_0 5_1 and future major versions as 5 plus`() {
        listOf("5.0.0", "5.1.0", "6.0.0").forEach { version ->
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
