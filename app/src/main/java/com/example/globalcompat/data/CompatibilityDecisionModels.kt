package com.example.globalcompat.data

enum class CompatibilityDecisionStatus {
    NO_ACTION_REQUIRED,
    VERIFIED_WORKFLOW_AVAILABLE,
    DIAGNOSTIC_ONLY,
    CURRENT_WORKFLOW_NOT_APPLICABLE,
    UNKNOWN,
    BLOCKED_BY_KNOWN_RULE,
}

enum class ApplicableWorkflow {
    NONE,
    HUAWEI_MICROG_COMPAT,
}

enum class CompatibilityNextAction {
    KEEP_CURRENT_ENVIRONMENT,
    PREPARE_VERIFIED_WORKFLOW,
    RUN_DIAGNOSTICS,
    WAIT_FOR_APPLICABLE_WORKFLOW,
    COLLECT_MORE_EVIDENCE,
    STOP_KNOWN_BLOCK,
}

data class DecisionEvidence(
    val code: String,
    val source: String,
    val observedValue: String,
)

data class DecisionMessage(
    val code: String,
    val message: String,
)

data class CompatibilityDecision(
    val decisionStatus: CompatibilityDecisionStatus,
    val validationLevel: GlobalValidationLevel,
    val googleEnvironmentAssessment: GoogleEnvironmentAssessment,
    val applicableWorkflow: ApplicableWorkflow,
    val confidence: DetectionConfidence,
    val evidence: List<DecisionEvidence>,
    val blockers: List<DecisionMessage>,
    val warnings: List<DecisionMessage>,
    val nextAction: CompatibilityNextAction,
    val catalogVersion: Long? = null,
    val catalogDigest: String? = null,
)
