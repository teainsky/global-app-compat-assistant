package com.example.globalcompat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEnvironmentDetectorTest {
    private val detector = EvidenceBasedRuntimeEnvironmentDetector()

    @Test
    fun `HarmonyOS 4 Android compatible runtime remains native compatibility branch`() {
        val result = detector.detect(probe(romVersion = "4.2.0"))

        assertEquals(RuntimeEnvironment.HARMONY_ANDROID_COMPAT, result.environment)
    }

    @Test
    fun `HarmonyOS 6 host conflicting with HarmonyOS 4 Android guest is third party runtime`() {
        val result = detector.detect(
            probe(
                romVersion = "4.2.0",
                properties = mapOf("const.ohos.fullname" to "HarmonyOS-6.1.0.135"),
            ),
        )

        assertEquals(RuntimeEnvironment.THIRD_PARTY_COMPAT_RUNTIME, result.environment)
        assertTrue(result.evidence.any { it.key == "runtime.host_guest_conflict" })
    }

    @Test
    fun `trusted compatibility runtime evidence wins without guessing from brand`() {
        val result = detector.detect(
            probe(
                romVersion = "4.2.0",
                evidence = listOf(
                    DetectionEvidence("runtime.trusted_installer", "com.zhuoyi.appstore.lite"),
                ),
            ),
        )

        assertEquals(RuntimeEnvironment.THIRD_PARTY_COMPAT_RUNTIME, result.environment)
    }

    private fun probe(
        romVersion: String,
        properties: Map<String, String> = emptyMap(),
        evidence: List<DetectionEvidence> = emptyList(),
    ) = RuntimeEnvironmentProbe(
        android = AndroidPlatform(
            apiLevel = 31,
            release = "12",
            securityPatch = null,
            buildDisplay = "HarmonyOS 4 guest",
            buildIncremental = "1",
            fingerprint = "guest/fingerprint",
        ),
        rom = RomIdentification(
            family = RomFamily.HARMONY_OS,
            displayName = "HarmonyOS",
            version = romVersion,
            confidence = DetectionConfidence.HIGH,
            evidence = emptyList(),
        ),
        properties = properties,
        trustedThirdPartyEvidence = evidence,
    )
}
