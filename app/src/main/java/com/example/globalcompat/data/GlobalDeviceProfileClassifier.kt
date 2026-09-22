package com.example.globalcompat.data

import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.CatalogVerifiedDeviceCompatibilityRecord

class GlobalDeviceProfileClassifier(
    private val oemRegistry: OemBrandRegistry = OemBrandRegistry(),
    private val verifiedDeviceRecords: List<CatalogVerifiedDeviceCompatibilityRecord> =
        BuiltInComponentCatalog.catalog.verifiedDeviceRecords,
) {
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
        val googleEnvironment = googleEnvironment(components, compatibilityLayerConfirmed)
        val validationLevel = validationLevel(
            device = device,
            android = android,
            rom = rom,
            platformFamily = platformFamily,
            oemKnown = oem.isKnown,
            trustedEnvironmentVerified = trustedEnvironmentVerified,
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
            googleEnvironment = googleEnvironment,
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
                add(DetectionEvidence("google.environment", googleEnvironment.name))
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

    private fun googleEnvironment(
        components: List<SystemComponent>,
        compatibilityLayerConfirmed: Boolean,
    ): GoogleEnvironment {
        if (components.any { it.presence == ComponentPresence.CHECK_FAILED }) {
            return GoogleEnvironment.UNKNOWN
        }
        val playServices = components.firstOrNull { it.id == ComponentId.GOOGLE_PLAY_SERVICES }
        val playStore = components.firstOrNull { it.id == ComponentId.GOOGLE_PLAY_STORE }
        val servicesUsable = playServices.isUsable()
        val storeUsable = playStore.isUsable()
        return when {
            servicesUsable && storeUsable -> GoogleEnvironment.GMS_COMPLETE
            compatibilityLayerConfirmed -> GoogleEnvironment.COMPATIBILITY_LAYER
            playServices.isPresent() || playStore.isPresent() -> GoogleEnvironment.GMS_PARTIAL
            playServices != null && playStore != null -> GoogleEnvironment.GMS_ABSENT
            else -> GoogleEnvironment.UNKNOWN
        }
    }

    private fun validationLevel(
        device: DeviceIdentity,
        android: AndroidPlatform,
        rom: RomIdentification,
        platformFamily: PlatformFamily,
        oemKnown: Boolean,
        trustedEnvironmentVerified: Boolean,
    ): GlobalValidationLevel {
        val exactRecord = verifiedDeviceRecords.any { record ->
            record.deviceModel == device.model &&
                record.deviceFamily == device.model &&
                record.romFamily == rom.family.catalogRomFamily() &&
                record.harmonyOsVersion == rom.version &&
                record.androidApiLevel == android.apiLevel
        }
        return when {
            exactRecord -> GlobalValidationLevel.DEVICE_VERIFIED
            trustedEnvironmentVerified -> GlobalValidationLevel.ENVIRONMENT_VERIFIED
            oemKnown && rom.family != RomFamily.UNKNOWN -> GlobalValidationLevel.PROBABLE
            else -> GlobalValidationLevel.UNKNOWN
        }
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
    }
}
