package com.example.globalcompat.catalog

import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedComponentCatalogMatcherTest {
    private val matcher = TrustedComponentCatalogMatcher()
    private val builtIn = BuiltInComponentCatalog.catalog

    @Test
    fun `Pura 70 Pro Plus HarmonyOS 4_2 matches official Huawei hw pair`() {
        val selection = matcher.select(builtIn, huaweiRequest())

        assertEquals(
            listOf(
                "com.google.android.gms-252432032-hw.apk",
                "com.android.vending-84022632-hw.apk",
            ),
            selection.compatibleArtifacts.map { it.artifactName },
        )
        assertTrue(selection.compatibleArtifacts.all { it.variant == ComponentVariant.HUAWEI_HW })
        assertTrue(selection.recommendedArtifacts.isEmpty())
    }

    @Test
    fun `Huawei plan cannot match ordinary custom ROM microG artifacts`() {
        val customRomRelease = candidateRelease().copy(
            releaseId = "custom-rom-build",
            status = ComponentReleaseStatus.VERIFIED,
            artifacts = candidateRelease().artifacts.map { artifact ->
                artifact.copy(
                    artifactName = artifact.artifactName.replace("-hw.apk", ".apk"),
                    variant = ComponentVariant.CUSTOM_ROM,
                    status = ComponentReleaseStatus.VERIFIED,
                )
            },
        )
        val catalog = builtIn.copy(releases = listOf(customRomRelease))

        val selection = matcher.select(catalog, huaweiRequest())

        assertTrue(selection.compatibleArtifacts.isEmpty())
        assertTrue(selection.recommendedArtifacts.isEmpty())
    }

    @Test
    fun `HarmonyOS 5 plus always returns empty component set`() {
        val request = huaweiRequest().copy(
            planId = CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN,
            deviceCategory = DeviceCategory.HARMONYOS_5_PLUS,
            systemVersion = "5.1",
        )

        val selection = matcher.select(builtIn, request)

        assertTrue(selection.compatibleReleases.isEmpty())
        assertTrue(selection.compatibleArtifacts.isEmpty())
        assertTrue(selection.recommendedArtifacts.isEmpty())
    }

    @Test
    fun `candidate newer release cannot replace verified release`() {
        val verified = verifiedRelease()
        val candidate = candidateRelease()
        val catalog = builtIn.copy(releases = listOf(verified, candidate))

        val selection = matcher.select(catalog, huaweiRequest())

        assertEquals(ComponentReleaseStatus.CANDIDATE, matcher.defaultStatusForNewRelease(catalog))
        assertEquals(verified.releaseId, selection.recommendedRelease?.releaseId)
        assertTrue(selection.recommendedArtifacts.all { it.status == ComponentReleaseStatus.VERIFIED })
        assertTrue(selection.compatibleReleases.any { it.status == ComponentReleaseStatus.CANDIDATE })
    }

    @Test
    fun `blocked release is never compatible or recommended`() {
        val verified = verifiedRelease()
        val blocked = candidateRelease().copy(
            releaseId = "blocked-newer-release",
            status = ComponentReleaseStatus.BLOCKED,
            artifacts = candidateRelease().artifacts.map {
                it.copy(status = ComponentReleaseStatus.BLOCKED)
            },
        )
        val catalog = builtIn.copy(releases = listOf(verified, blocked))

        val selection = matcher.select(catalog, huaweiRequest())

        assertEquals(listOf(verified.releaseId), selection.compatibleReleases.map { it.releaseId })
        assertEquals(verified.releaseId, selection.recommendedRelease?.releaseId)
    }

    @Test
    fun `missing hash and signing digest is explicitly not ready for download verification`() {
        val selection = matcher.select(builtIn, huaweiRequest())

        assertTrue(selection.compatibleArtifacts.all { it.sha256 == null })
        assertTrue(selection.compatibleArtifacts.all { it.signingCertificateDigest == null })
        assertTrue(selection.verificationAssessments.all { assessment ->
            assessment.readiness ==
                ArtifactVerificationReadiness.NOT_READY_MISSING_INTEGRITY_METADATA
        })
        assertTrue(selection.verificationAssessments.all { !it.isReadyForDownloadVerification })
        assertTrue(selection.verificationAssessments.all {
            it.missingMetadata == listOf("sha256", "signingCertificateDigest")
        })
    }

    @Test
    fun `unknown device does not receive component recommendation`() {
        val request = huaweiRequest().copy(
            planId = CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN,
            deviceCategory = DeviceCategory.UNKNOWN,
            deviceFamily = "UNKNOWN_DEVICE",
        )

        val selection = matcher.select(builtIn, request)

        assertTrue(selection.compatibleArtifacts.isEmpty())
        assertNull(selection.recommendedRelease)
        assertTrue(selection.recommendedArtifacts.isEmpty())
    }

    @Test
    fun `supported Huawei EMUI request matches only Huawei hw artifacts`() {
        val selection = matcher.select(
            builtIn,
            huaweiRequest().copy(
                systemFamily = CatalogSystemFamily.HUAWEI_EMUI,
                systemVersion = "14.2",
            ),
        )

        assertFalse(selection.compatibleArtifacts.isEmpty())
        assertTrue(selection.compatibleArtifacts.all { it.variant == ComponentVariant.HUAWEI_HW })
    }

    @Test
    fun `catalog source policy contains only approved official channels`() {
        assertEquals(
            setOf(
                ComponentSourceType.OFFICIAL_MICROG_GITHUB,
                ComponentSourceType.OFFICIAL_HUAWEI_APPGALLERY,
            ),
            builtIn.verificationPolicy.allowedSourceTypes,
        )
    }

    private fun candidateRelease(): ComponentRelease = builtIn.releases.single()

    private fun verifiedRelease(): ComponentRelease = candidateRelease().copy(
        releaseId = "microg-huawei-hw-v0.3.15.250932-verified",
        releaseVersion = "v0.3.15.250932",
        status = ComponentReleaseStatus.VERIFIED,
        publishedAt = "2026-04-24",
        artifacts = candidateRelease().artifacts.map { artifact ->
            artifact.copy(
                releaseVersion = "v0.3.15.250932",
                artifactName = when (artifact.packageName) {
                    "com.google.android.gms" -> "com.google.android.gms-250932030-hw.apk"
                    else -> "com.android.vending-84022630-hw.apk"
                },
                verifiedDeviceFamilies = listOf(PURA_70_PRO_PLUS),
                status = ComponentReleaseStatus.VERIFIED,
                publishedAt = "2026-04-24",
            )
        },
    )

    private fun huaweiRequest() = CatalogMatchRequest(
        planId = CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN,
        deviceCategory = DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT,
        deviceFamily = PURA_70_PRO_PLUS,
        systemFamily = CatalogSystemFamily.HUAWEI_HARMONY_OS,
        systemVersion = "4.2",
    )

    private companion object {
        const val PURA_70_PRO_PLUS = "HUAWEI_PURA_70_PRO_PLUS"
    }
}
