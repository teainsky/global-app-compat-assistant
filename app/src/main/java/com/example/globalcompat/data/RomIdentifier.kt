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

        return detectHarmonyOs(probe) ?: detect(
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
        ) ?: detect(
            properties = properties,
            family = RomFamily.OXYGEN_OS,
            displayName = "OxygenOS",
            versionKeys = listOf("ro.oxygen.version", "ro.oxygen.version.name"),
        ) ?: detectVivoOs(properties)
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
            ) ?: detect(
                properties = properties,
                family = RomFamily.REALME_UI,
                displayName = "realme UI",
                versionKeys = listOf("ro.build.version.realmeui"),
            ) ?: detect(
                properties = properties,
                family = RomFamily.MY_OS,
                displayName = "MyOS",
                versionKeys = listOf("ro.build.version.myos"),
            ) ?: detect(
                properties = properties,
                family = RomFamily.NOTHING_OS,
                displayName = "Nothing OS",
                versionKeys = listOf("ro.build.version.nothing"),
            ) ?: detect(
                properties = properties,
                family = RomFamily.TECNO_HIOS,
                displayName = "HiOS",
                versionKeys = listOf("ro.transsion.hios.version"),
            ) ?: detect(
                properties = properties,
                family = RomFamily.INFINIX_XOS,
                displayName = "XOS",
                versionKeys = listOf("ro.transsion.xos.version"),
            ) ?: detect(
                properties = properties,
                family = RomFamily.ITEL_OS,
                displayName = "itel OS",
                versionKeys = listOf("ro.transsion.itelos.version"),
            ) ?: detect(
                properties = properties,
                family = RomFamily.TCL_UI,
                displayName = "TCL UI",
                versionKeys = listOf("ro.tcl.ui.version"),
            ) ?: detectOemFallback(probe)
            ?: unknown(probe)
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

    private fun detectVivoOs(properties: Map<String, String>): RomIdentification? {
        val name = properties["ro.vivo.os.name"]
        val version = properties["ro.vivo.os.version"]
        val family = when {
            name?.contains("origin", ignoreCase = true) == true -> RomFamily.ORIGIN_OS
            name?.contains("funtouch", ignoreCase = true) == true -> RomFamily.FUNTOUCH_OS
            else -> return null
        }

        val evidence = buildList {
            add(DetectionEvidence("ro.vivo.os.name", name))
            version?.let { add(DetectionEvidence("ro.vivo.os.version", it)) }
        }
        return RomIdentification(
            family = family,
            displayName = if (family == RomFamily.ORIGIN_OS) "OriginOS" else "Funtouch OS",
            version = version ?: name,
            confidence = DetectionConfidence.HIGH,
            evidence = evidence,
        )
    }

    private fun detectOemFallback(probe: RomProbe): RomIdentification? {
        val resolution = OEM_REGISTRY.resolve(probe.manufacturer, probe.brand)
        val registration = resolution.registration ?: return probe.buildDisplay
            .takeIf { it.contains("aosp", ignoreCase = true) }
            ?.let {
                RomIdentification(
                    family = RomFamily.AOSP,
                    displayName = "AOSP",
                    version = null,
                    confidence = DetectionConfidence.MEDIUM,
                    evidence = listOf(DetectionEvidence("build.display", it)),
                )
            }
        val family = registration.probableRomFamily ?: return null
        return RomIdentification(
            family = family,
            displayName = family.displayName(),
            version = null,
            confidence = DetectionConfidence.MEDIUM,
            evidence = listOf(
                DetectionEvidence("oem.registry", registration.id),
                DetectionEvidence("oem.alias", resolution.matchedAlias.orEmpty()),
            ),
        )
    }

    private fun RomFamily.displayName(): String = name
        .lowercase()
        .split('_')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun detectHarmonyOs(probe: RomProbe): RomIdentification? {
        val propertyEvidence = HARMONY_VERSION_KEYS.mapNotNull { key ->
            if (probe.properties.containsKey(key)) {
                DetectionEvidence(key, probe.properties[key].orEmpty())
            } else {
                null
            }
        }
        val displayEvidence = probe.buildDisplay
            .takeIf { HARMONY_MARKER.containsMatchIn(it) }
            ?.let { DetectionEvidence("build.display", it) }
        if (propertyEvidence.isEmpty() && displayEvidence == null) return null

        val evidence = propertyEvidence + listOfNotNull(displayEvidence)
        if (evidence.any { HARMONY_NEXT.containsMatchIn(it.value) }) {
            val contradictsLegacyBranch = evidence.mapNotNull { it.value.harmonyVersion() }
                .mapNotNull { VERSION_NUMBER.matchAt(it, 0)?.value?.toIntOrNull() }
                .any { it in 1..4 }
            if (contradictsLegacyBranch) return harmonyVersionUnknown(evidence)
            return RomIdentification(
                family = RomFamily.HARMONY_OS_5_PLUS,
                displayName = "HarmonyOS 5+",
                version = evidence.firstNotNullOfOrNull { it.value.harmonyVersion() } ?: "5+",
                confidence = DetectionConfidence.HIGH,
                evidence = evidence,
            )
        }

        val parsedVersions = evidence.mapNotNull { it.value.harmonyVersion() }
        val majorVersions = parsedVersions.mapNotNull { version ->
            VERSION_NUMBER.matchAt(version, 0)?.value?.toIntOrNull()
        }.distinct()
        val hasUnparseableEvidence = propertyEvidence.any { it.value.harmonyVersion() == null } ||
            parsedVersions.isEmpty()
        if (hasUnparseableEvidence || majorVersions.size != 1) {
            return harmonyVersionUnknown(evidence)
        }

        val version = parsedVersions.first()
        val major = majorVersions.single()
        val family = when {
            major in 1..4 -> RomFamily.HARMONY_OS
            major >= 5 -> RomFamily.HARMONY_OS_5_PLUS
            else -> return harmonyVersionUnknown(evidence)
        }
        return RomIdentification(
            family = family,
            displayName = if (family == RomFamily.HARMONY_OS) "HarmonyOS" else "HarmonyOS 5+",
            version = version,
            confidence = DetectionConfidence.HIGH,
            evidence = evidence,
        )
    }

    private fun harmonyVersionUnknown(evidence: List<DetectionEvidence>) = RomIdentification(
        family = RomFamily.HARMONY_VERSION_UNKNOWN,
        displayName = "HarmonyOS（版本无法确认）",
        version = null,
        confidence = DetectionConfidence.UNKNOWN,
        evidence = evidence,
    )

    private fun String.harmonyVersion(): String? {
        val trimmed = trim()
        if (trimmed.isEmpty()) return null
        HARMONY_VERSION_ONLY.matchEntire(trimmed)?.let { return it.groupValues[1] }
        return HARMONY_VERSION_IN_DISPLAY.find(trimmed)?.groupValues?.get(1)
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
        private val HARMONY_MARKER = Regex("(?i)harmony\\s*os")
        private val HARMONY_NEXT = Regex("(?i)harmony\\s*os\\s*next|^next$")
        private val HARMONY_VERSION_ONLY = Regex("(?i)^(?:harmony\\s*os\\s*)?(\\d+(?:\\.\\d+)*)$")
        private val HARMONY_VERSION_IN_DISPLAY = Regex("(?i)harmony\\s*os\\s*(\\d+(?:\\.\\d+)*)")

        val PROPERTY_KEYS = setOf(
            "const.ohos.fullname",
            "const.product.software.version",
            "hw_sc.build.platform.version",
            "ro.build.version.harmony",
            "ro.huawei.build.version.emui",
            "ro.mi.os.version.name",
            "ro.mi.os.version.incremental",
            "ro.miui.ui.version.name",
            "ro.build.version.opporom",
            "ro.oxygen.version",
            "ro.oxygen.version.name",
            "ro.vivo.os.name",
            "ro.vivo.os.version",
            "ro.build.version.magic",
            "ro.build.version.magicui",
            "ro.build.version.oneui",
            "ro.build.version.realmeui",
            "ro.build.version.myos",
            "ro.build.version.nothing",
            "ro.transsion.hios.version",
            "ro.transsion.xos.version",
            "ro.transsion.itelos.version",
            "ro.tcl.ui.version",
        )
        private val OEM_REGISTRY = OemBrandRegistry()
    }
}
