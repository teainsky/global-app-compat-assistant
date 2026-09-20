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
                "com.google.android.gms-250932032-hw.apk",
                "com.android.vending-84022632-hw.apk",
            ),
            selection.compatibleArtifacts.map { it.artifactFilename },
        )
        assertTrue(selection.compatibleArtifacts.all { it.variant == ComponentVariant.HUAWEI_HW })
        assertTrue(selection.recommendedArtifacts.isEmpty())
        assertTrue(selection.installableArtifacts.isEmpty())
        assertEquals(
            CompatibilityValidationStatus.CANDIDATE,
            selection.compatibleReleases.single().compatibilityStatus,
        )
        assertTrue(selection.compatibleArtifacts.all {
            it.compatibilityStatus == CompatibilityValidationStatus.UNTESTED &&
                it.integrityStatus == ArtifactIntegrityStatus.SOURCE_VERIFIED
        })
    }

    @Test
    fun `Huawei plan cannot match ordinary custom ROM microG artifacts`() {
        val customRomRelease = candidateRelease().copy(
            releaseId = "custom-rom-build",
            compatibilityStatus = CompatibilityValidationStatus.DEVICE_VERIFIED,
            artifacts = candidateRelease().artifacts.map { artifact ->
                artifact.copy(
                    artifactFilename = artifact.artifactFilename?.replace("-hw.apk", ".apk"),
                    variant = ComponentVariant.CUSTOM_ROM,
                    compatibilityStatus = CompatibilityValidationStatus.DEVICE_VERIFIED,
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

        assertEquals(
            CompatibilityValidationStatus.CANDIDATE,
            matcher.defaultStatusForNewRelease(catalog),
        )
        assertEquals(
            CompatibilityValidationStatus.UNTESTED,
            matcher.defaultStatusForNewArtifact(catalog),
        )
        assertEquals(verified.releaseId, selection.recommendedRelease?.releaseId)
        assertTrue(selection.recommendedArtifacts.all {
            it.compatibilityStatus == CompatibilityValidationStatus.DEVICE_VERIFIED
        })
        assertTrue(selection.compatibleReleases.any {
            it.compatibilityStatus == CompatibilityValidationStatus.CANDIDATE
        })
    }

    @Test
    fun `blocked release is never compatible or recommended`() {
        val verified = verifiedRelease()
        val blocked = candidateRelease().copy(
            releaseId = "blocked-newer-release",
            compatibilityStatus = CompatibilityValidationStatus.BLOCKED,
            artifacts = candidateRelease().artifacts.map {
                it.copy(compatibilityStatus = CompatibilityValidationStatus.BLOCKED)
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
        assertTrue(selection.verificationAssessments.all { !it.meetsArtifactInstallationGate })
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
                ComponentSourceType.OFFICIAL_MICROG_DOWNLOAD_PAGE,
                ComponentSourceType.OFFICIAL_HUAWEI_APPGALLERY,
            ),
            builtIn.verificationPolicy.allowedSourceTypes,
        )
    }

    @Test
    fun `release tag never determines Huawei GmsCore artifact filename`() {
        val release = candidateRelease()
        val gmsCore = release.artifacts.single { it.packageName == "com.google.android.gms" }

        assertEquals("v0.3.16.252432", release.releaseTag)
        assertEquals("0.3.16.252432", release.releaseVersion)
        assertEquals("250932032", gmsCore.artifactVersionCode)
        assertEquals("com.google.android.gms-250932032-hw.apk", gmsCore.artifactFilename)
        assertFalse(gmsCore.artifactFilename == "com.google.android.gms-252432032-hw.apk")
    }

    @Test
    fun `Huawei pair exactly matches explicit official release metadata`() {
        val actual = candidateRelease().artifacts.associate { artifact ->
            artifact.componentId to listOf(
                artifact.packageName,
                artifact.releaseVersion,
                artifact.artifactFilename,
                artifact.artifactVersionCode,
                artifact.metadataSource?.name,
                artifact.sourceReleaseUrl,
            )
        }

        assertEquals(
            mapOf(
                "microg_services_huawei_compatible" to listOf(
                    "com.google.android.gms",
                    "0.3.16.252432",
                    "com.google.android.gms-250932032-hw.apk",
                    "250932032",
                    "OFFICIAL_MICROG_GITHUB",
                    OFFICIAL_RELEASE_URL,
                ),
                "microg_companion_huawei_compatible" to listOf(
                    "com.android.vending",
                    "0.3.16.252432",
                    "com.android.vending-84022632-hw.apk",
                    "84022632",
                    "OFFICIAL_MICROG_GITHUB",
                    OFFICIAL_RELEASE_URL,
                ),
            ),
            actual,
        )
    }

    @Test
    fun `missing explicit artifact metadata fails closed without filename inference`() {
        val gmsCore = candidateRelease().artifacts.single {
            it.packageName == "com.google.android.gms"
        }
        val incompleteArtifacts = listOf(
            "artifactFilename" to gmsCore.copy(artifactFilename = null),
            "artifactVersionCode" to gmsCore.copy(artifactVersionCode = null),
            "metadataSource" to gmsCore.copy(metadataSource = null),
            "sourceReleaseUrl" to gmsCore.copy(sourceReleaseUrl = null),
        )

        incompleteArtifacts.forEach { (missingField, incompleteArtifact) ->
            val incompleteRelease = candidateRelease().copy(
                artifacts = candidateRelease().artifacts.map { artifact ->
                    if (artifact.componentId == incompleteArtifact.componentId) {
                        incompleteArtifact
                    } else {
                        artifact
                    }
                },
            )
            val selection = matcher.select(
                builtIn.copy(releases = listOf(incompleteRelease)),
                huaweiRequest(),
            )

            assertTrue("$missingField must fail closed", selection.compatibleReleases.isEmpty())
            assertTrue("$missingField must not infer artifacts", selection.compatibleArtifacts.isEmpty())
            assertTrue("$missingField must not recommend", selection.recommendedArtifacts.isEmpty())
            assertTrue("$missingField must not install", selection.installableArtifacts.isEmpty())
        }
    }

    @Test
    fun `hash and signature verification changes integrity only`() {
        val candidate = candidateRelease().artifacts.first()

        val verifiedIntegrity = matcher.applyIntegrityEvidence(
            candidate,
            ArtifactIntegrityEvidence(
                sourceVerified = true,
                sha256 = "trusted-sha256",
                sha256Matches = true,
                signingCertificateDigest = "trusted-signing-certificate",
                signingCertificateMatches = true,
            ),
        )

        assertEquals(ArtifactIntegrityStatus.SIGNATURE_VERIFIED, verifiedIntegrity.integrityStatus)
        assertEquals(
            CompatibilityValidationStatus.UNTESTED,
            verifiedIntegrity.compatibilityStatus,
        )
    }

    @Test
    fun `automatic installation requires integrity and device compatibility together`() {
        val deviceVerifiedWithoutIntegrity = verifiedRelease().copy(
            artifacts = verifiedRelease().artifacts.map {
                it.copy(integrityStatus = ArtifactIntegrityStatus.SOURCE_VERIFIED)
            },
        )
        val withoutIntegrity = matcher.select(
            builtIn.copy(releases = listOf(deviceVerifiedWithoutIntegrity)),
            huaweiRequest(),
        )

        assertFalse(withoutIntegrity.recommendedArtifacts.isEmpty())
        assertTrue(withoutIntegrity.installableArtifacts.isEmpty())

        val fullyEligible = deviceVerifiedWithoutIntegrity.copy(
            artifacts = deviceVerifiedWithoutIntegrity.artifacts.map {
                it.copy(integrityStatus = ArtifactIntegrityStatus.SIGNATURE_VERIFIED)
            },
        )
        val eligible = matcher.select(
            builtIn.copy(releases = listOf(fullyEligible)),
            huaweiRequest(),
        )

        assertEquals(2, eligible.installableArtifacts.size)
        assertTrue(eligible.verificationAssessments.all { it.meetsArtifactInstallationGate })
    }

    private fun candidateRelease(): ComponentRelease = builtIn.releases.single()

    private fun verifiedRelease(): ComponentRelease = candidateRelease().copy(
        releaseId = "microg-huawei-hw-v0.3.15.250932-verified",
        releaseTag = "v0.3.15.250932",
        releaseVersion = "0.3.15.250932",
        compatibilityStatus = CompatibilityValidationStatus.DEVICE_VERIFIED,
        publishedAt = "2026-04-24",
        artifacts = candidateRelease().artifacts.map { artifact ->
            artifact.copy(
                releaseVersion = "0.3.15.250932",
                artifactFilename = when (artifact.packageName) {
                    "com.google.android.gms" -> "com.google.android.gms-250932030-hw.apk"
                    else -> "com.android.vending-84022630-hw.apk"
                },
                artifactVersionCode = when (artifact.packageName) {
                    "com.google.android.gms" -> "250932030"
                    else -> "84022630"
                },
                sourceReleaseUrl =
                    "https://github.com/microg/GmsCore/releases/tag/v0.3.15.250932",
                verifiedDeviceFamilies = listOf(PURA_70_PRO_PLUS),
                sha256 = "verified-sha256-${artifact.componentId}",
                signingCertificateDigest = "verified-certificate-${artifact.componentId}",
                integrityStatus = ArtifactIntegrityStatus.SIGNATURE_VERIFIED,
                compatibilityStatus = CompatibilityValidationStatus.DEVICE_VERIFIED,
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
        const val OFFICIAL_RELEASE_URL =
            "https://github.com/microg/GmsCore/releases/tag/v0.3.16.252432"
    }
}
