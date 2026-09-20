package com.example.globalcompat.data

data class RomProbe(
    val manufacturer: String,
    val brand: String,
    val buildDisplay: String,
    val properties: Map<String, String>,
)

fun interface RomIdentifier {
    fun identify(probe: RomProbe): RomIdentification
}
class PropertyBasedRomIdentifier : RomIdentifier {
    override fun identify(probe: RomProbe): RomIdentification {
        val properties = probe.properties.filterValues { it.isNotBlank() }

        return detectHarmonyOs5Plus(probe, properties) ?: detect(
            properties = properties,
            family = RomFamily.HARMONY_OS,
            displayName = "HarmonyOS",
            versionKeys = HARMONY_VERSION_KEYS,
        ) ?: detect(
            properties = properties,
            family = RomFamily.HYPER_OS,
            displayName = "HyperOS",
            versionKeys = listOf(
                "ro.mi.os.version.name",
                "ro.mi.os.version.incremental",
            ),
        ) ?: detect(
            properties = properties,
            family = RomFamily.COLOR_OS,
            displayName = "ColorOS",
            versionKeys = listOf("ro.build.version.opporom"),
        ) ?: detectOriginOs(properties)
            ?: detect(
                properties = properties,
                family = RomFamily.MAGIC_OS,
                displayName = "MagicOS",
                versionKeys = listOf(
                    "ro.build.version.magic",
                    "ro.build.version.magicui",
                ),
            ) ?: detect(
                properties = properties,
                family = RomFamily.ONE_UI,
                displayName = "One UI",
                versionKeys = listOf("ro.build.version.oneui"),
            ) ?: unknown(probe)
    }

    private fun detect(
        properties: Map<String, String>,
        family: RomFamily,
        displayName: String,
        versionKeys: List<String>,
    ): RomIdentification? {
        val evidence = versionKeys.mapNotNull { key ->
            properties[key]?.let { DetectionEvidence(key, it) }
        }
        if (evidence.isEmpty()) return null

        return RomIdentification(
            family = family,
            displayName = displayName,
            version = evidence.first().value,
            confidence = DetectionConfidence.HIGH,
            evidence = evidence,
        )
    }

    private fun detectOriginOs(properties: Map<String, String>): RomIdentification? {
        val name = properties["ro.vivo.os.name"]
        val version = properties["ro.vivo.os.version"]
        if (name?.contains("origin", ignoreCase = true) != true) return null

        val evidence = buildList {
            add(DetectionEvidence("ro.vivo.os.name", name))
            version?.let { add(DetectionEvidence("ro.vivo.os.version", it)) }
        }
        return RomIdentification(
            family = RomFamily.ORIGIN_OS,
            displayName = "OriginOS",
            version = version ?: name,
            confidence = DetectionConfidence.HIGH,
            evidence = evidence,
        )
    }

    private fun detectHarmonyOs5Plus(
        probe: RomProbe,
        properties: Map<String, String>,
    ): RomIdentification? {
        val harmonyEvidence = HARMONY_VERSION_KEYS.mapNotNull { key ->
            properties[key]?.let { DetectionEvidence(key, it) }
        }
        val explicitNextEvidence = harmonyEvidence.filter {
            it.value.contains("next", ignoreCase = true)
        }
        val displayEvidence = probe.buildDisplay
            .takeIf { it.contains("harmonyos next", ignoreCase = true) }
            ?.let { DetectionEvidence("build.display", it) }
        val versionFiveOrNewer = harmonyEvidence.firstOrNull {
            VERSION_NUMBER.find(it.value)?.value?.toIntOrNull()?.let { major -> major >= 5 } == true
        }
        val evidence = buildList {
            addAll(explicitNextEvidence)
            displayEvidence?.let(::add)
            if (isEmpty()) versionFiveOrNewer?.let(::add)
        }
        if (evidence.isEmpty()) return null

        return RomIdentification(
            family = RomFamily.HARMONY_OS_5_PLUS,
            displayName = "HarmonyOS 5+",
            version = harmonyEvidence.firstOrNull()?.value ?: "5+",
            confidence = if (explicitNextEvidence.isNotEmpty() || displayEvidence != null) {
                DetectionConfidence.HIGH
            } else {
                DetectionConfidence.MEDIUM
            },
            evidence = evidence,
        )
    }

    private fun unknown(probe: RomProbe): RomIdentification {
        val evidence = listOf(
            DetectionEvidence("manufacturer", probe.manufacturer),
            DetectionEvidence("brand", probe.brand),
            DetectionEvidence("build.display", probe.buildDisplay),
        ).filter { it.value.isNotBlank() }

        return RomIdentification(
            family = RomFamily.UNKNOWN,
            displayName = "未识别",
            version = null,
            confidence = DetectionConfidence.UNKNOWN,
            evidence = evidence,
        )
    }

    companion object {
        private val HARMONY_VERSION_KEYS = listOf(
            "hw_sc.build.platform.version",
            "ro.build.version.harmony",
        )
        private val VERSION_NUMBER = Regex("\\d+")

        val PROPERTY_KEYS = setOf(
            "hw_sc.build.platform.version",
            "ro.build.version.harmony",
            "ro.huawei.build.version.emui",
            "ro.mi.os.version.name",
            "ro.mi.os.version.incremental",
            "ro.miui.ui.version.name",
            "ro.build.version.opporom",
            "ro.vivo.os.name",
            "ro.vivo.os.version",
            "ro.build.version.magic",
            "ro.build.version.magicui",
            "ro.build.version.oneui",
        )
    }
}
