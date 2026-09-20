package com.example.globalcompat.baseline

import android.content.Context
import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.data.DeviceEnvironmentScanner
import com.example.globalcompat.data.EnvironmentReport

data class DeviceBaselineScanResult(
    val environment: EnvironmentReport,
    val componentComparisons: List<OfficialComponentComparison>,
)

class DeviceBaselineScanner(
    context: Context,
    private val environmentScanner: DeviceEnvironmentScanner = DeviceEnvironmentScanner(context),
    private val fingerprintScanner: InstalledComponentFingerprintScanner =
        InstalledComponentFingerprintScanner(AndroidInstalledPackageLookup(context.packageManager)),
    private val componentMatcher: OfficialComponentMatcher =
        OfficialComponentMatcher(BuiltInComponentCatalog.catalog),
) {
    fun scan(): DeviceBaselineScanResult {
        val environment = environmentScanner.scan()
        val fingerprints = fingerprintScanner.scan()
        return DeviceBaselineScanResult(
            environment = environment,
            componentComparisons = componentMatcher.compare(fingerprints),
        )
    }
}
