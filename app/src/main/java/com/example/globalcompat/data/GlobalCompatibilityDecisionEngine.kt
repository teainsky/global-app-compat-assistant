package com.example.globalcompat.data

import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.CatalogMatchRequest
import com.example.globalcompat.catalog.CatalogSystemFamily
import com.example.globalcompat.catalog.CompatibilityValidationStatus
import com.example.globalcompat.catalog.ComponentCatalog
import com.example.globalcompat.catalog.TrustedComponentCatalogMatcher

class GlobalCompatibilityDecisionEngine(
    private val catalog: ComponentCatalog = BuiltInComponentCatalog.catalog,
    private val catalogMatcher: TrustedComponentCatalogMatcher = TrustedComponentCatalogMatcher(),
) {
    fun decide(profile: DeviceProfile): CompatibilityDecision {
        val baseEvidence = profile.evidence.map { evidence ->
            DecisionEvidence(
                code = evidence.key,
                source = "DeviceProfile",
                observedValue = evidence.value,
            )
        } + listOf(
            DecisionEvidence("device.model", "DeviceProfile", profile.model),
            DecisionEvidence("device.family", "DeviceProfile", profile.deviceFamily),
            DecisionEvidence("system.version", "DeviceProfile", profile.osVersion),
            DecisionEvidence(
                "android.api_level",
                "DeviceProfile",
                profile.androidApiLevel.toString(),
            ),
        )

        if (profile.googleEnvironment == GoogleEnvironment.GMS_COMPLETE) {
            return decision(
                profile = profile,
                status = CompatibilityDecisionStatus.NO_ACTION_REQUIRED,
                workflow = ApplicableWorkflow.NONE,
                confidence = completeEnvironmentConfidence(profile.validationLevel),
                evidence = baseEvidence,
                warnings = listOf(
                    message(
                        "GMS_RUNTIME_HEALTH_OUTSIDE_SCAN",
                        "已确认核心 Google 组件存在且启用；账号、认证与运行时健康仍不在本次扫描范围。",
                    ),
                ),
                nextAction = CompatibilityNextAction.KEEP_CURRENT_ENVIRONMENT,
            )
        }

        if (profile.platformFamily == PlatformFamily.HARMONY_NATIVE) {
            return decision(
                profile = profile,
                status = CompatibilityDecisionStatus.CURRENT_WORKFLOW_NOT_APPLICABLE,
                workflow = ApplicableWorkflow.NONE,
                confidence = DetectionConfidence.HIGH,
                evidence = baseEvidence,
                warnings = listOf(
                    message(
                        "LEGACY_HARMONY_WORKFLOW_NOT_APPLICABLE",
                        "HarmonyOS 5+ 不适用 HarmonyOS 1–4 工作流；这不代表设备永远不支持全球应用。",
                    ),
                ),
                nextAction = CompatibilityNextAction.WAIT_FOR_APPLICABLE_WORKFLOW,
            )
        }

        if (profile.validationLevel == GlobalValidationLevel.BLOCKED) {
            return decision(
                profile = profile,
                status = CompatibilityDecisionStatus.BLOCKED_BY_KNOWN_RULE,
                workflow = ApplicableWorkflow.NONE,
                confidence = DetectionConfidence.HIGH,
                evidence = baseEvidence,
                blockers = listOf(
                    message(
                        "TRUSTED_BLOCK_RULE",
                        "可信规则明确阻止当前环境进入兼容工作流。",
                    ),
                ),
                nextAction = CompatibilityNextAction.STOP_KNOWN_BLOCK,
            )
        }

        val verifiedWorkflow = verifiedWorkflow(profile)
        if (profile.validationLevel == GlobalValidationLevel.DEVICE_VERIFIED &&
            verifiedWorkflow != null
        ) {
            return decision(
                profile = profile,
                status = CompatibilityDecisionStatus.VERIFIED_WORKFLOW_AVAILABLE,
                workflow = verifiedWorkflow,
                confidence = DetectionConfidence.HIGH,
                evidence = baseEvidence + DecisionEvidence(
                    code = "signed.catalog.workflow",
                    source = "SignedCompatibilityCatalog",
                    observedValue = verifiedWorkflow.name,
                ),
                nextAction = CompatibilityNextAction.PREPARE_VERIFIED_WORKFLOW,
            )
        }

        if (profile.googleEnvironment in ENVIRONMENT_PROBLEM_STATES) {
            return decision(
                profile = profile,
                status = CompatibilityDecisionStatus.DIAGNOSTIC_ONLY,
                workflow = ApplicableWorkflow.NONE,
                confidence = diagnosticConfidence(profile.validationLevel),
                evidence = baseEvidence,
                warnings = listOf(
                    message(
                        "EXACT_DEVICE_VERIFICATION_REQUIRED",
                        "检测到 Google 环境问题，但没有精确 DEVICE_VERIFIED 签名工作流；不能解锁安装。",
                    ),
                ),
                nextAction = CompatibilityNextAction.RUN_DIAGNOSTICS,
            )
        }

        return decision(
            profile = profile,
            status = CompatibilityDecisionStatus.UNKNOWN,
            workflow = ApplicableWorkflow.NONE,
            confidence = DetectionConfidence.UNKNOWN,
            evidence = baseEvidence,
            warnings = listOf(
                message(
                    "INSUFFICIENT_GLOBAL_EVIDENCE",
                    "未知设备进入 generic Android fallback；不会因品牌或 ROM 未识别而标记为不支持。",
                ),
            ),
            nextAction = CompatibilityNextAction.COLLECT_MORE_EVIDENCE,
        )
    }

    private fun verifiedWorkflow(profile: DeviceProfile): ApplicableWorkflow? {
        if (profile.platformFamily != PlatformFamily.HARMONY_ANDROID_COMPAT ||
            profile.romFamily != RomFamily.HARMONY_OS
        ) {
            return null
        }
        val selection = catalogMatcher.select(
            catalog = catalog,
            request = CatalogMatchRequest(
                planId = CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN,
                deviceCategory = DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT,
                deviceFamily = profile.deviceFamily,
                systemFamily = CatalogSystemFamily.HUAWEI_HARMONY_OS,
                systemVersion = profile.osVersion,
                androidApiLevel = profile.androidApiLevel,
            ),
        )
        val release = selection.recommendedRelease ?: return null
        if (release.compatibilityStatus != CompatibilityValidationStatus.DEVICE_VERIFIED ||
            selection.installableArtifacts.isEmpty()
        ) {
            return null
        }
        return ApplicableWorkflow.HUAWEI_MICROG_COMPAT
    }

    private fun decision(
        profile: DeviceProfile,
        status: CompatibilityDecisionStatus,
        workflow: ApplicableWorkflow,
        confidence: DetectionConfidence,
        evidence: List<DecisionEvidence>,
        blockers: List<DecisionMessage> = emptyList(),
        warnings: List<DecisionMessage> = emptyList(),
        nextAction: CompatibilityNextAction,
    ) = CompatibilityDecision(
        decisionStatus = status,
        validationLevel = profile.validationLevel,
        googleEnvironment = profile.googleEnvironment,
        applicableWorkflow = workflow,
        confidence = confidence,
        evidence = evidence,
        blockers = blockers,
        warnings = warnings,
        nextAction = nextAction,
    )

    private fun completeEnvironmentConfidence(level: GlobalValidationLevel) = when (level) {
        GlobalValidationLevel.DEVICE_VERIFIED,
        GlobalValidationLevel.ENVIRONMENT_VERIFIED,
        -> DetectionConfidence.HIGH
        GlobalValidationLevel.PROBABLE -> DetectionConfidence.MEDIUM
        GlobalValidationLevel.UNKNOWN,
        GlobalValidationLevel.BLOCKED,
        -> DetectionConfidence.LOW
    }

    private fun diagnosticConfidence(level: GlobalValidationLevel) = when (level) {
        GlobalValidationLevel.ENVIRONMENT_VERIFIED -> DetectionConfidence.HIGH
        GlobalValidationLevel.PROBABLE,
        GlobalValidationLevel.DEVICE_VERIFIED,
        -> DetectionConfidence.MEDIUM
        GlobalValidationLevel.UNKNOWN,
        GlobalValidationLevel.BLOCKED,
        -> DetectionConfidence.LOW
    }

    private fun message(code: String, message: String) = DecisionMessage(code, message)

    private companion object {
        val ENVIRONMENT_PROBLEM_STATES = setOf(
            GoogleEnvironment.GMS_ABSENT,
            GoogleEnvironment.GMS_PARTIAL,
            GoogleEnvironment.COMPATIBILITY_LAYER,
        )
    }
}
