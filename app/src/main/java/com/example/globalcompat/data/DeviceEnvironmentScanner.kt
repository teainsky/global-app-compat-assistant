package com.example.globalcompat.data

import android.content.Context
import android.os.Build
import com.example.globalcompat.catalog.CatalogSnapshot
import com.example.globalcompat.catalog.RuntimeTrustedCatalogRepository

class DeviceEnvironmentScanner(
    context: Context,
    private val catalogSnapshot: CatalogSnapshot? =
        RuntimeTrustedCatalogRepository.instance.currentSnapshot(),
    private val propertyReader: SystemPropertyReader = GetPropSystemPropertyReader(),
    private val romIdentifier: RomIdentifier = PropertyBasedRomIdentifier(),
    private val componentScanner: ComponentScanner = AndroidComponentScanner(context.packageManager),
    private val compatibilityLayerDetector: GoogleCompatibilityLayerDetector =
        DeferredGoogleCompatibilityLayerDetector(),
    private val compatibilityPlanMatcher: CompatibilityPlanMatcher =
        RuleBasedCompatibilityPlanMatcher(catalogSnapshot),
    private val deviceProfileClassifier: GlobalDeviceProfileClassifier =
        GlobalDeviceProfileClassifier(catalogSnapshot = catalogSnapshot),
    private val runtimeEnvironmentDetector: RuntimeEnvironmentDetector =
        EvidenceBasedRuntimeEnvironmentDetector(),
    private val thirdPartyRuntimeEvidenceProvider: ThirdPartyRuntimeEvidenceProvider =
        AndroidThirdPartyRuntimeEvidenceProvider(context.applicationContext),
    private val compatibilityDecisionEngine: GlobalCompatibilityDecisionEngine =
        GlobalCompatibilityDecisionEngine(catalogSnapshot),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun scan(): EnvironmentReport {
        val device = DeviceIdentity(
            brand = Build.BRAND.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            board = Build.BOARD.orEmpty(),
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
        )
        val android = AndroidPlatform(
            apiLevel = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE.orEmpty(),
            securityPatch = Build.VERSION.SECURITY_PATCH?.takeIf(String::isNotBlank),
            buildDisplay = Build.DISPLAY.orEmpty(),
            buildIncremental = Build.VERSION.INCREMENTAL.orEmpty(),
            fingerprint = Build.FINGERPRINT.orEmpty(),
        )
        val properties = propertyReader.read(PropertyBasedRomIdentifier.PROPERTY_KEYS)
        val rom = romIdentifier.identify(
            RomProbe(
                manufacturer = device.manufacturer,
                brand = device.brand,
                buildDisplay = android.buildDisplay,
                properties = properties,
            ),
        )
        val runtimeEnvironment = runtimeEnvironmentDetector.detect(
            RuntimeEnvironmentProbe(
                android = android,
                rom = rom,
                properties = properties,
                trustedThirdPartyEvidence = thirdPartyRuntimeEvidenceProvider.read(),
            ),
        )
        val components = componentScanner.scan()
        val googleCompatibilityLayer = compatibilityLayerDetector.detect(components)
        val deviceProfile = deviceProfileClassifier.classify(
            device = device,
            android = android,
            rom = rom,
            components = components,
            runtimeEnvironment = runtimeEnvironment,
        )
        val compatibilityDecision = compatibilityDecisionEngine.decide(deviceProfile)
        val compatibilityPlan = compatibilityPlanMatcher.match(
            CompatibilityContext(
                device = device,
                android = android,
                rom = rom,
                components = components,
                deviceProfile = deviceProfile,
            ),
        )

        return EnvironmentReport(
            schemaVersion = 5,
            scannedAtEpochMillis = clock(),
            device = device,
            android = android,
            rom = rom,
            components = components,
            googleCompatibilityLayer = googleCompatibilityLayer,
            deviceProfile = deviceProfile,
            compatibilityDecision = compatibilityDecision,
            compatibilityPlan = compatibilityPlan,
        )
    }
}
