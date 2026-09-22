package com.example.globalcompat.data

import com.example.globalcompat.catalog.CatalogVerifiedDeviceCompatibilityRecord
import com.example.globalcompat.catalog.CatalogSnapshot
import com.example.globalcompat.catalog.CompatibilityValidationStatus
import com.example.globalcompat.catalog.RuntimeTrustedCatalogRepository

class GlobalDeviceProfileClassifier(
    private val oemRegistry: OemBrandRegistry = OemBrandRegistry(),
    private val catalogSnapshot: CatalogSnapshot? =
        RuntimeTrustedCatalogRepository.instance.currentSnapshot(),
) {
    private val verifiedDeviceRecords: List<CatalogVerifiedDeviceCompatibilityRecord>
        get() = catalogSnapshot?.catalog?.verifiedDeviceRecords.orEmpty()
    fun classify(
        device: DeviceIdentity,
        android: AndroidPlatform,
        rom: RomIdentification,
        components: List<SystemComponent>,
        marketVariant: MarketVariant = MarketVariant.UNKNOWN,
        compatibilityLayerConfirmed: Boolean = false,
        trustedEnvironmentVerified: Boolean = false,
    ): DeviceProfile {
        val oem = oemRegistry.resolve(device.manufacturer, device.brand)
        val platformFamily = platformFamily(rom)
        val exactVerifiedRecord = exactVerifiedRecord(device, android, rom)
        val googleEnvironmentAssessment = googleEnvironmentAssessment(
            components = components,
            compatibilityLayerConfirmed = compatibilityLayerConfirmed,
            exactVerifiedRecord = exactVerifiedRecord,
        )
        val validationLevel = validationLevel(
            rom = rom,
            oemKnown = oem.isKnown,
            trustedEnvironmentVerified = trustedEnvironmentVerified,
            exactVerifiedRecord = exactVerifiedRecord,
        )
        return DeviceProfile(
            manufacturer = device.manufacturer,
            brand = device.brand,
            model = device.model,
            deviceFamily = device.model.takeIf(String::isNotBlank) ?: UNKNOWN_VALUE,
            marketVariant = marketVariant,
            platformFamily = platformFamily,
            osFamily = when (platformFamily) {
                PlatformFamily.HARMONY_ANDROID_COMPAT,
                PlatformFamily.HARMONY_NATIVE,
                -> OsFamily.HARMONY_OS
                PlatformFamily.STANDARD_ANDROID,
                PlatformFamily.ANDROID_DERIVED,
                -> OsFamily.ANDROID
                PlatformFamily.UNKNOWN -> if (android.apiLevel > 0) OsFamily.ANDROID else OsFamily.UNKNOWN
            },
            osVersion = if (platformFamily in HARMONY_PLATFORMS) {
                rom.version.orEmpty()
            } else {
                android.release
            },
            androidApiLevel = android.apiLevel,
            romFamily = rom.family,
            romVersion = rom.version,
            googleEnvironmentAssessment = googleEnvironmentAssessment,
            installationCapability = installationCapability(platformFamily, android.apiLevel),
            validationLevel = validationLevel,
            evidence = buildList {
                add(DetectionEvidence("market.variant", marketVariant.name))
                add(
                    DetectionEvidence(
                        "market.variant.policy",
                        "No language, SIM, IP or timezone inference",
                    ),
                )
                add(DetectionEvidence("platform.family", platformFamily.name))
                add(
                    DetectionEvidence(
                        "google.component_set_state",
                        googleEnvironmentAssessment.componentSetState.name,
                    ),
                )
                add(
                    DetectionEvidence(
                        "google.component_trust",
                        googleEnvironmentAssessment.componentTrust.name,
                    ),
                )
                add(
                    DetectionEvidence(
                        "google.functional_health",
                        googleEnvironmentAssessment.functionalHealth.name,
                    ),
                )
                add(
                    DetectionEvidence(
                        "google.play_certification",
                        googleEnvironmentAssessment.playCertification.name,
                    ),
                )
                add(DetectionEvidence("validation.level", validationLevel.name))
                add(
                    DetectionEvidence(
                        "oem.registry",
                        oem.registration?.id ?: UNKNOWN_VALUE,
                    ),
                )
                addAll(rom.evidence)
            },
        )
    }

    private fun platformFamily(rom: RomIdentification): PlatformFamily = when {
        rom.family == RomFamily.HARMONY_OS_5_PLUS ||
            rom.family == RomFamily.HARMONY_OS && rom.version.majorVersion()?.let { it >= 5 } == true ->
            PlatformFamily.HARMONY_NATIVE
        rom.family == RomFamily.HARMONY_OS -> PlatformFamily.HARMONY_ANDROID_COMPAT
        rom.family == RomFamily.UNKNOWN -> PlatformFamily.UNKNOWN
        rom.family in STANDARD_ANDROID_ROMS -> PlatformFamily.STANDARD_ANDROID
        else -> PlatformFamily.ANDROID_DERIVED
    }

    private fun String?.majorVersion(): Int? =
        this?.let { VERSION_NUMBER.find(it)?.value?.toIntOrNull() }

    private fun googleEnvironmentAssessment(
        components: List<SystemComponent>,
        compatibilityLayerConfirmed: Boolean,
        exactVerifiedRecord: CatalogVerifiedDeviceCompatibilityRecord?,
    ): GoogleEnvironmentAssessment {
        val playServices = components.firstOrNull { it.id == ComponentId.GOOGLE_PLAY_SERVICES }
        val playStore = components.firstOrNull { it.id == ComponentId.GOOGLE_PLAY_STORE }
        val componentSetState = when {
            listOfNotNull(playServices, playStore).any {
                it.presence == ComponentPresence.CHECK_FAILED
            } -> GoogleComponentSetState.UNKNOWN
            playServices.isUsable() && playStore.isUsable() ->
                GoogleComponentSetState.COMPLETE
            playServices.isPresent() || playStore.isPresent() ->
                GoogleComponentSetState.PARTIAL
            playServices != null && playStore != null -> GoogleComponentSetState.ABSENT
            else -> GoogleComponentSetState.UNKNOWN
        }
        val pairMatchesVerifiedRecord = exactVerifiedRecord != null &&
            componentSetState == GoogleComponentSetState.COMPLETE &&
            components.matchVerifiedRecord(exactVerifiedRecord)
        val hasVerifiedRecordMismatch = exactVerifiedRecord != null &&
            components.hasVerifiedRecordMismatch(exactVerifiedRecord)
        val componentTrust = when {
            pairMatchesVerifiedRecord -> ComponentTrust.TRUSTED
            hasVerifiedRecordMismatch -> ComponentTrust.MISMATCH
            compatibilityLayerConfirmed -> ComponentTrust.COMPATIBILITY_REPORTED
            componentSetState == GoogleComponentSetState.COMPLETE ||
                componentSetState == GoogleComponentSetState.PARTIAL -> ComponentTrust.UNVERIFIED
            componentSetState == GoogleComponentSetState.ABSENT -> ComponentTrust.UNKNOWN
            else -> ComponentTrust.UNKNOWN
        }
        val functionalHealth = when {
            pairMatchesVerifiedRecord -> FunctionalHealth.USER_CONFIRMED
            componentSetState == GoogleComponentSetState.UNKNOWN -> FunctionalHealth.UNKNOWN
            else -> FunctionalHealth.UNTESTED
        }
        return GoogleEnvironmentAssessment(
            componentSetState = componentSetState,
            componentTrust = componentTrust,
            functionalHealth = functionalHealth,
            playCertification = PlayCertification.UNKNOWN,
            evidence = listOf(
                DetectionEvidence("component_set.scan", componentSetState.name),
                DetectionEvidence(
                    "component_trust.source",
                    when (componentTrust) {
                        ComponentTrust.TRUSTED -> "SIGNED_DEVICE_VERIFIED_CATALOG_RECORD"
                        ComponentTrust.COMPATIBILITY_REPORTED -> "COMPATIBILITY_LAYER_REPORT"
                        ComponentTrust.MISMATCH -> "SIGNED_CATALOG_VERSION_MISMATCH"
                        ComponentTrust.UNVERIFIED -> "PACKAGE_PRESENCE_ONLY"
                        ComponentTrust.UNKNOWN -> "NO_TRUST_EVIDENCE"
                    },
                ),
                DetectionEvidence(
                    "functional_health.source",
                    if (functionalHealth == FunctionalHealth.USER_CONFIRMED) {
                        "SIGNED_DEVICE_VERIFICATION_EVIDENCE"
                    } else {
                        "NO_FUNCTIONAL_TEST_EVIDENCE"
                    },
                ),
                DetectionEvidence(
                    "play_certification.policy",
                    "NOT_INFERRED_FROM_BRAND_ROM_COMPONENTS_OR_COMPATIBILITY_LAYER",
                ),
            ),
        )
    }

    private fun validationLevel(
        rom: RomIdentification,
        oemKnown: Boolean,
        trustedEnvironmentVerified: Boolean,
        exactVerifiedRecord: CatalogVerifiedDeviceCompatibilityRecord?,
    ): GlobalValidationLevel {
        return when {
            exactVerifiedRecord != null -> GlobalValidationLevel.DEVICE_VERIFIED
            trustedEnvironmentVerified -> GlobalValidationLevel.ENVIRONMENT_VERIFIED
            oemKnown && rom.family != RomFamily.UNKNOWN -> GlobalValidationLevel.PROBABLE
            else -> GlobalValidationLevel.UNKNOWN
        }
    }

    private fun exactVerifiedRecord(
        device: DeviceIdentity,
        android: AndroidPlatform,
        rom: RomIdentification,
    ): CatalogVerifiedDeviceCompatibilityRecord? = verifiedDeviceRecords.firstOrNull { record ->
        record.compatibilityStatus == CompatibilityValidationStatus.DEVICE_VERIFIED &&
            record.deviceModel == device.model &&
            record.deviceFamily == device.model &&
            record.romFamily == rom.family.catalogRomFamily() &&
            record.harmonyOsVersion == rom.version &&
            record.androidApiLevel == android.apiLevel
    }

    private fun List<SystemComponent>.matchVerifiedRecord(
        record: CatalogVerifiedDeviceCompatibilityRecord,
    ): Boolean = REQUIRED_GOOGLE_COMPONENTS.all { componentId ->
        val component = firstOrNull { it.id == componentId } ?: return@all false
        val expectedVersion = record.componentVersionCodes[component.packageName]
        component.isUsable() &&
            component.versionCode != null &&
            component.versionCode.toString() == expectedVersion
    }

    private fun List<SystemComponent>.hasVerifiedRecordMismatch(
        record: CatalogVerifiedDeviceCompatibilityRecord,
    ): Boolean = filter { it.id in REQUIRED_GOOGLE_COMPONENTS && it.isPresent() }.any { component ->
        val expectedVersion = record.componentVersionCodes[component.packageName]
        expectedVersion != null && component.versionCode != null &&
            component.versionCode.toString() != expectedVersion
    }

    private fun installationCapability(
        platformFamily: PlatformFamily,
        androidApiLevel: Int,
    ): InstallationCapability = when (platformFamily) {
        PlatformFamily.HARMONY_ANDROID_COMPAT ->
            InstallationCapability.LEGACY_HARMONY_COMPATIBLE
        PlatformFamily.HARMONY_NATIVE -> InstallationCapability.NOT_APPLICABLE
        else -> if (androidApiLevel > 0) {
            InstallationCapability.USER_CONFIRMED_PACKAGE_INSTALL
        } else {
            InstallationCapability.UNKNOWN
        }
    }

    private fun SystemComponent?.isUsable(): Boolean =
        this?.presence == ComponentPresence.PRESENT && enabled == true

    private fun SystemComponent?.isPresent(): Boolean =
        this?.presence == ComponentPresence.PRESENT

    private fun RomFamily.catalogRomFamily(): String = when (this) {
        RomFamily.HARMONY_OS,
        RomFamily.HARMONY_OS_5_PLUS,
        -> "HARMONY_OS"
        else -> name
    }

    private companion object {
        const val UNKNOWN_VALUE = "UNKNOWN"
        val HARMONY_PLATFORMS = setOf(
            PlatformFamily.HARMONY_ANDROID_COMPAT,
            PlatformFamily.HARMONY_NATIVE,
        )
        val STANDARD_ANDROID_ROMS = setOf(
            RomFamily.AOSP,
            RomFamily.PIXEL_ANDROID,
            RomFamily.MOTOROLA_ANDROID,
            RomFamily.ASUS_ANDROID,
            RomFamily.SONY_ANDROID,
            RomFamily.HMD_ANDROID,
        )
        val VERSION_NUMBER = Regex("\\d+")
        val REQUIRED_GOOGLE_COMPONENTS = setOf(
            ComponentId.GOOGLE_PLAY_SERVICES,
            ComponentId.GOOGLE_PLAY_STORE,
        )
    }
}
