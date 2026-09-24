package com.example.globalcompat.data

import android.content.Context
import android.os.Build

data class RuntimeEnvironmentProbe(
    val android: AndroidPlatform,
    val rom: RomIdentification,
    val properties: Map<String, String>,
    val trustedThirdPartyEvidence: List<DetectionEvidence> = emptyList(),
)

data class RuntimeEnvironmentDetection(
    val environment: RuntimeEnvironment,
    val evidence: List<DetectionEvidence>,
)

fun interface RuntimeEnvironmentDetector {
    fun detect(probe: RuntimeEnvironmentProbe): RuntimeEnvironmentDetection
}

class EvidenceBasedRuntimeEnvironmentDetector : RuntimeEnvironmentDetector {
    override fun detect(probe: RuntimeEnvironmentProbe): RuntimeEnvironmentDetection {
        val hostEvidence = HOST_SYSTEM_VERSION_KEYS.mapNotNull { key ->
            probe.properties[key]?.takeIf(String::isNotBlank)?.let {
                DetectionEvidence(key, it)
            }
        }
        val hostHarmonyMajor = hostEvidence.asSequence()
            .mapNotNull { it.value.harmonyMajorVersion() }
            .firstOrNull()
        val guestHarmonyMajor = probe.rom.version.majorVersion()
        val hostGuestConflict = hostHarmonyMajor != null && (
            hostHarmonyMajor >= 5 && probe.android.apiLevel > 0 ||
                guestHarmonyMajor != null && hostHarmonyMajor != guestHarmonyMajor
            )

        if (probe.trustedThirdPartyEvidence.isNotEmpty() || hostGuestConflict) {
            return RuntimeEnvironmentDetection(
                environment = RuntimeEnvironment.THIRD_PARTY_COMPAT_RUNTIME,
                evidence = buildList {
                    addAll(probe.trustedThirdPartyEvidence)
                    addAll(hostEvidence)
                    if (hostGuestConflict) {
                        add(
                            DetectionEvidence(
                                "runtime.host_guest_conflict",
                                "hostHarmonyMajor=$hostHarmonyMajor, " +
                                    "guestRom=${probe.rom.version.orEmpty()}, " +
                                    "guestAndroidApi=${probe.android.apiLevel}",
                            ),
                        )
                    }
                },
            )
        }

        val environment = when {
            probe.rom.family == RomFamily.HARMONY_OS && guestHarmonyMajor in 1..4 ->
                RuntimeEnvironment.HARMONY_ANDROID_COMPAT
            probe.rom.family == RomFamily.HARMONY_OS_5_PLUS ||
                probe.rom.family == RomFamily.HARMONY_VERSION_UNKNOWN ->
                RuntimeEnvironment.UNKNOWN
            probe.android.apiLevel > 0 -> RuntimeEnvironment.NATIVE_ANDROID
            else -> RuntimeEnvironment.UNKNOWN
        }
        return RuntimeEnvironmentDetection(
            environment = environment,
            evidence = listOf(DetectionEvidence("runtime.classification", environment.name)),
        )
    }

    private fun String?.harmonyMajorVersion(): Int? {
        val value = this?.takeIf(String::isNotBlank) ?: return null
        return HARMONY_VERSION.find(value)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun String?.majorVersion(): Int? = this
        ?.let { VERSION_NUMBER.find(it)?.value }
        ?.toIntOrNull()

    companion object {
        val HOST_SYSTEM_VERSION_KEYS = setOf(
            "const.ohos.fullname",
            "const.product.software.version",
        )
        private val HARMONY_VERSION = Regex(
            "(?i)(?:harmony\\s*os|openharmony)[-\\s]*([0-9]+)(?:\\.[0-9]+)*",
        )
        private val VERSION_NUMBER = Regex("[0-9]+")
    }
}

fun interface ThirdPartyRuntimeEvidenceProvider {
    fun read(): List<DetectionEvidence>
}

class AndroidThirdPartyRuntimeEvidenceProvider(
    private val context: Context,
) : ThirdPartyRuntimeEvidenceProvider {
    override fun read(): List<DetectionEvidence> {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName)
                    .installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()?.lowercase() ?: return emptyList()
        return if (installer in TRUSTED_COMPAT_RUNTIME_INSTALLERS) {
            listOf(DetectionEvidence("runtime.trusted_installer", installer))
        } else {
            emptyList()
        }
    }

    private companion object {
        val TRUSTED_COMPAT_RUNTIME_INSTALLERS = setOf(
            "com.zhuoyi.appstore.lite",
            "com.droi.tong",
        )
    }
}
