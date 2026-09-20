package com.example.globalcompat.data

enum class CompatibilityPlanStatus {
    READY,
    CONFIGURATION_REQUIRED,
    REPAIR_REQUIRED,
    UNSUPPORTED,
}

data class ComponentVersionPolicy(
    val minVersion: String? = null,
    val maxVersion: String? = null,
    val blockedVersions: List<String> = emptyList(),
    val verifiedDeviceFamilies: List<String> = emptyList(),
    val verifiedSystemVersions: List<String> = emptyList(),
)

data class RequiredCompatibilityComponent(
    val componentId: String,
    val displayName: String,
    val versionPolicy: ComponentVersionPolicy = ComponentVersionPolicy(),
)

data class PlanEvidence(
    val code: String,
    val source: String,
    val observedValue: String,
    val description: String,
)

enum class CompatibilityWarningCode {
    INSTALL_WORKFLOW_NOT_APPLICABLE,
    COMPONENT_SCAN_INCOMPLETE,
    PARTIAL_GOOGLE_COMPONENTS,
    MICROG_RUNTIME_NOT_ASSESSED,
    VERSION_POLICY_NOT_CONFIGURED,
    COMPONENT_HEALTH_NOT_VERIFIED,
    GOOGLE_COMPONENT_SET_INCOMPLETE,
    DEVICE_RULE_NOT_VERIFIED,
    HMS_NOT_GMS_SIGNAL,
    INSUFFICIENT_EVIDENCE,
}

data class CompatibilityWarning(
    val code: CompatibilityWarningCode,
    val message: String,
)

data class CompatibilityPlan(
    val deviceCategory: DeviceCategory,
    val planId: CompatibilityPlanId,
    val status: CompatibilityPlanStatus,
    val requiredComponents: List<RequiredCompatibilityComponent>,
    val installationOrder: List<String>,
    val evidence: List<PlanEvidence>,
    val warnings: List<CompatibilityWarning>,
    val confidence: DetectionConfidence,
)

data class CompatibilityContext(
    val device: DeviceIdentity,
    val android: AndroidPlatform,
    val rom: RomIdentification,
    val components: List<SystemComponent>,
)
