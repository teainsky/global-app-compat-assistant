package com.example.globalcompat.simulation

import com.example.globalcompat.catalog.ArtifactIntegrityStatus
import com.example.globalcompat.catalog.CompatibilityValidationStatus
import com.example.globalcompat.catalog.ComponentSourceType
import com.example.globalcompat.catalog.SourceAvailabilityStatus
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.data.GlobalValidationLevel

enum class SimulationPlanStatus {
    NO_ACTION_REQUIRED,
    SIMULATION_READY,
    REVIEW_REQUIRED,
    BLOCKED,
}

enum class SimulationFlowStage {
    DEVICE_DETECTION,
    PLAN_MATCHING,
    OFFICIAL_COMPONENT_SELECTION,
    INTEGRITY_CHECK,
    CURRENT_COMPONENT_ASSESSMENT,
    INSTALLATION_ORDER,
    NEXT_ACTION,
}

enum class SimulationStageStatus {
    PASS,
    SKIPPED,
    REVIEW,
    BLOCKED,
}

data class SimulationStageResult(
    val stage: SimulationFlowStage,
    val status: SimulationStageStatus,
    val message: String,
)

enum class CurrentComponentState {
    OFFICIAL_ARTIFACT_MATCH,
    COMPATIBILITY_SIGNATURE_REPORTED,
    NOT_INSTALLED,
    VERSION_MISMATCH,
    SIGNATURE_MISMATCH,
    UNREADABLE,
    UNKNOWN,
}

enum class SimulatedInstallAction {
    INSTALL,
    REPLACE_VERSION,
}

enum class SimulationNextAction {
    NO_ACTION_REQUIRED,
    REVIEW_SIMULATED_STEPS,
    VERIFY_CURRENT_ARTIFACT,
    STOP_SIGNATURE_MISMATCH,
    STOP_UNSUPPORTED_SYSTEM,
    STOP_OFFICIAL_COMPONENT_UNAVAILABLE,
    STOP_INTEGRITY_EVIDENCE_INCOMPLETE,
    STOP_INSUFFICIENT_EVIDENCE,
}

data class SimulatedArtifact(
    val componentId: String,
    val packageName: String,
    val artifactFilename: String,
    val versionCode: String,
    val versionName: String,
    val sourceType: ComponentSourceType,
    val sourceAvailability: SourceAvailabilityStatus,
    val integrityStatus: ArtifactIntegrityStatus,
    val compatibilityStatus: CompatibilityValidationStatus,
)

data class CurrentComponentDecision(
    val componentId: String,
    val packageName: String,
    val state: CurrentComponentState,
    val detail: String,
)

data class SimulatedInstallationStep(
    val order: Int,
    val componentId: String,
    val packageName: String,
    val artifactFilename: String,
    val action: SimulatedInstallAction,
)

data class SimulatedInstallationPlan(
    val schemaVersion: Int,
    val simulationOnly: Boolean,
    val realInstallationAllowed: Boolean,
    val status: SimulationPlanStatus,
    val deviceCategory: DeviceCategory,
    val compatibilityPlanId: CompatibilityPlanId,
    val deviceModel: String,
    val systemVersion: String,
    val androidApiLevel: Int,
    val validationLevel: GlobalValidationLevel,
    val selectedReleaseTag: String?,
    val selectedArtifacts: List<SimulatedArtifact>,
    val currentComponents: List<CurrentComponentDecision>,
    val installationOrder: List<SimulatedInstallationStep>,
    val nextAction: SimulationNextAction,
    val stages: List<SimulationStageResult>,
    val warnings: List<String>,
)
