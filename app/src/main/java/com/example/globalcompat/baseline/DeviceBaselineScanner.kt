package com.example.globalcompat.baseline

import android.content.Context
import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.data.DeviceEnvironmentScanner
import com.example.globalcompat.data.EnvironmentReport
import com.example.globalcompat.installation.InstallationExecutionGate
import com.example.globalcompat.installation.InstallationSessionPlan
import com.example.globalcompat.simulation.SimulatedInstallationPlan
import com.example.globalcompat.simulation.SimulatedInstallationPlanner

data class DeviceBaselineScanResult(
    val environment: EnvironmentReport,
    val componentComparisons: List<OfficialComponentComparison>,
    val simulatedInstallationPlan: SimulatedInstallationPlan,
    val installationSessionPlan: InstallationSessionPlan,
)

class DeviceBaselineScanner(
    context: Context,
    private val environmentScanner: DeviceEnvironmentScanner = DeviceEnvironmentScanner(context),
    private val fingerprintScanner: InstalledComponentFingerprintScanner =
        InstalledComponentFingerprintScanner(AndroidInstalledPackageLookup(context.packageManager)),
    private val componentMatcher: OfficialComponentMatcher =
        OfficialComponentMatcher(BuiltInComponentCatalog.catalog),
    private val simulatedInstallationPlanner: SimulatedInstallationPlanner =
        SimulatedInstallationPlanner(BuiltInComponentCatalog.catalog),
    private val installationExecutionGate: InstallationExecutionGate =
        InstallationExecutionGate(BuiltInComponentCatalog.catalog),
) {
    fun scan(): DeviceBaselineScanResult {
        val environment = environmentScanner.scan()
        val fingerprints = fingerprintScanner.scan()
        val comparisons = componentMatcher.compare(
            fingerprints = fingerprints,
            context = ComponentMatchContext(
                manufacturer = environment.device.manufacturer,
                romFamily = environment.rom.family,
                romVersion = environment.rom.version,
            ),
        )
        val simulatedPlan = simulatedInstallationPlanner.create(
            environment = environment,
            comparisons = comparisons,
        )
        return DeviceBaselineScanResult(
            environment = environment,
            componentComparisons = comparisons,
            simulatedInstallationPlan = simulatedPlan,
            installationSessionPlan = installationExecutionGate.evaluate(simulatedPlan),
        )
    }
}
