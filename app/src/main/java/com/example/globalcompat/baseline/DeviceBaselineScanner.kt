package com.example.globalcompat.baseline

import android.content.Context
import com.example.globalcompat.artifact.OnDeviceArtifactAuditReport
import com.example.globalcompat.catalog.CatalogSnapshot
import com.example.globalcompat.catalog.RuntimeTrustedCatalogRepository
import com.example.globalcompat.catalog.TrustedCatalogRepository
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
    val catalogSnapshot: CatalogSnapshot?,
)

class DeviceBaselineScanner(
    private val context: Context,
    private val catalogRepository: TrustedCatalogRepository =
        RuntimeTrustedCatalogRepository.instance,
    private val fingerprintScanner: InstalledComponentFingerprintScanner =
        InstalledComponentFingerprintScanner(AndroidInstalledPackageLookup(context.packageManager)),
) {
    fun scan(): DeviceBaselineScanResult {
        val snapshot = catalogRepository.currentSnapshot()
        val environment = DeviceEnvironmentScanner(context, catalogSnapshot = snapshot).scan()
        val fingerprints = fingerprintScanner.scan()
        val componentMatcher = OfficialComponentMatcher(snapshot?.catalog)
        val comparisons = componentMatcher.compare(
            fingerprints = fingerprints,
            context = ComponentMatchContext(
                manufacturer = environment.device.manufacturer,
                romFamily = environment.rom.family,
                romVersion = environment.rom.version,
            ),
        )
        val simulatedPlan = SimulatedInstallationPlanner(snapshot).create(
            environment = environment,
            comparisons = comparisons,
        )
        return DeviceBaselineScanResult(
            environment = environment,
            componentComparisons = comparisons,
            simulatedInstallationPlan = simulatedPlan,
            installationSessionPlan = InstallationExecutionGate(snapshot).evaluate(simulatedPlan),
            catalogSnapshot = snapshot,
        )
    }

    fun applyArtifactAudit(
        scanResult: DeviceBaselineScanResult,
        auditReport: OnDeviceArtifactAuditReport,
    ): DeviceBaselineScanResult {
        val snapshot = scanResult.catalogSnapshot
        val auditedHashes = auditReport.components.mapNotNull { component ->
            component.installedApkSha256?.let { component.packageName to it }
        }.toMap()
        val comparisons = OfficialComponentMatcher(snapshot?.catalog).compare(
            fingerprints = scanResult.componentComparisons.map { it.fingerprint },
            context = ComponentMatchContext(
                manufacturer = scanResult.environment.device.manufacturer,
                romFamily = scanResult.environment.rom.family,
                romVersion = scanResult.environment.rom.version,
            ),
            actualArtifactSha256ByPackage = auditedHashes,
        )
        val simulatedPlan = SimulatedInstallationPlanner(snapshot).create(
            environment = scanResult.environment,
            comparisons = comparisons,
        )
        return scanResult.copy(
            componentComparisons = comparisons,
            simulatedInstallationPlan = simulatedPlan,
            installationSessionPlan = InstallationExecutionGate(snapshot).evaluate(simulatedPlan),
        )
    }
}
