package com.example.globalcompat.data

import com.example.globalcompat.catalog.CatalogMatchRequest
import com.example.globalcompat.catalog.CatalogSnapshot
import com.example.globalcompat.catalog.CatalogSystemFamily
import com.example.globalcompat.catalog.CompatibilityValidationStatus
import com.example.globalcompat.catalog.RuntimeTrustedCatalogRepository
import com.example.globalcompat.catalog.TrustedComponentCatalogMatcher

class GlobalCompatibilityDecisionEngine(
    private val catalogSnapshot: CatalogSnapshot? =
        RuntimeTrustedCatalogRepository.instance.currentSnapshot(),
    private val catalogMatcher: TrustedComponentCatalogMatcher = TrustedComponentCatalogMatcher(),
    private val coveragePolicy: FreeMvpCoveragePolicy = FreeMvpCoveragePolicy(),
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
        val verifiedWorkflow = verifiedWorkflow(profile)
        val freeMvpCoverage = coveragePolicy.evaluate(profile, verifiedWorkflow)

        if (profile.runtimeEnvironment == RuntimeEnvironment.THIRD_PARTY_COMPAT_RUNTIME) {
            return decision(
                profile = profile,
                status = CompatibilityDecisionStatus.DIAGNOSTIC_ONLY,
                workflow = ApplicableWorkflow.NONE,
                confidence = DetectionConfidence.HIGH,
                evidence = baseEvidence,
                freeMvpCoverage = freeMvpCoverage,
                warnings = listOf(
                    message(
                        "THIRD_PARTY_COMPAT_RUNTIME_DIAGNOSTIC_ONLY",
                        "当前应用运行在兼容环境中，检测结果代表该兼容环境，不代表手机原生系统。",
                    ),
                ),
                nextAction = CompatibilityNextAction.RUN_DIAGNOSTICS,
            )
        }

        if (profile.platformFamily == PlatformFamily.HARMONY_NATIVE ||
            profile.platformFamily == PlatformFamily.HARMONY_VERSION_UNKNOWN
        ) {
            val versionUnknown = profile.platformFamily == PlatformFamily.HARMONY_VERSION_UNKNOWN
            return decision(
                profile = profile,
                status = CompatibilityDecisionStatus.CURRENT_WORKFLOW_NOT_APPLICABLE,
                workflow = ApplicableWorkflow.NONE,
                confidence = if (versionUnknown) {
                    DetectionConfidence.UNKNOWN
                } else {
                    DetectionConfidence.HIGH
                },
                evidence = baseEvidence,
                freeMvpCoverage = freeMvpCoverage,
                warnings = listOf(
                    message(
                        if (versionUnknown) {
                            "HARMONY_VERSION_UNKNOWN"
                        } else {
                            "LEGACY_HARMONY_WORKFLOW_NOT_APPLICABLE"
                        },
                        if (versionUnknown) {
                            "当前系统版本无法安全识别，暂不执行配置。"
                        } else {
                            "当前系统不适用现有 Android 兼容环境工作流；这不代表设备永远不支持全球应用。"
                        },
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
                freeMvpCoverage = freeMvpCoverage,
                blockers = listOf(
                    message(
                        "TRUSTED_BLOCK_RULE",
                        "可信规则明确阻止当前环境进入兼容工作流。",
                    ),
                ),
                nextAction = CompatibilityNextAction.STOP_KNOWN_BLOCK,
            )
        }

        if (catalogSnapshot == null) {
            return decision(
                profile = profile,
                status = if (
                    profile.googleEnvironmentAssessment.componentSetState ==
                    GoogleComponentSetState.UNKNOWN
                ) {
                    CompatibilityDecisionStatus.UNKNOWN
                } else {
                    CompatibilityDecisionStatus.DIAGNOSTIC_ONLY
                },
                workflow = ApplicableWorkflow.NONE,
                confidence = DetectionConfidence.LOW,
                evidence = baseEvidence,
                freeMvpCoverage = freeMvpCoverage,
                warnings = listOf(
                    message(
                        "TRUSTED_CATALOG_UNAVAILABLE",
                        "当前没有可信 catalog，仅允许基础诊断，不能解锁安装。",
                    ),
                ),
                nextAction = if (
                    profile.googleEnvironmentAssessment.componentSetState ==
                    GoogleComponentSetState.UNKNOWN
                ) {
                    CompatibilityNextAction.COLLECT_MORE_EVIDENCE
                } else {
                    CompatibilityNextAction.RUN_DIAGNOSTICS
                },
            )
        }

        if (profile.googleEnvironmentAssessment.isNoActionRequired(profile.validationLevel)) {
            return decision(
                profile = profile,
                status = CompatibilityDecisionStatus.NO_ACTION_REQUIRED,
                workflow = ApplicableWorkflow.NONE,
                confidence = DetectionConfidence.HIGH,
                evidence = baseEvidence,
                freeMvpCoverage = freeMvpCoverage,
                warnings = buildList {
                    if (profile.googleEnvironmentAssessment.playCertification ==
                        PlayCertification.UNKNOWN
                    ) {
                        add(
                            message(
                                "PLAY_CERTIFICATION_UNKNOWN",
                                "当前结论不代表 Play 认证已验证，也不泛化为 Google 生态完全健康。",
                            ),
                        )
                    }
                },
                nextAction = CompatibilityNextAction.KEEP_CURRENT_ENVIRONMENT,
            )
        }

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
                freeMvpCoverage = freeMvpCoverage,
                nextAction = CompatibilityNextAction.PREPARE_VERIFIED_WORKFLOW,
            )
        }

        if (profile.googleEnvironmentAssessment.componentSetState in DIAGNOSTIC_STATES) {
            return decision(
                profile = profile,
                status = CompatibilityDecisionStatus.DIAGNOSTIC_ONLY,
                workflow = ApplicableWorkflow.NONE,
                confidence = diagnosticConfidence(profile.validationLevel),
                evidence = baseEvidence,
                freeMvpCoverage = freeMvpCoverage,
                warnings = listOf(
                    message(
                        "EXACT_DEVICE_VERIFICATION_REQUIRED",
                        if (profile.googleEnvironmentAssessment.componentSetState ==
                            GoogleComponentSetState.COMPLETE
                        ) {
                            "核心组件存在且启用，但可信度或功能健康证据不足；不能据此判断无需处理。"
                        } else {
                            "检测到 Google 环境问题，但没有精确 DEVICE_VERIFIED 签名工作流；不能解锁安装。"
                        },
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
            freeMvpCoverage = freeMvpCoverage,
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
        val catalog = catalogSnapshot?.catalog ?: return null
        if (profile.runtimeEnvironment != RuntimeEnvironment.HARMONY_ANDROID_COMPAT ||
            profile.platformFamily != PlatformFamily.HARMONY_ANDROID_COMPAT ||
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
        freeMvpCoverage: FreeMvpCoverage,
        blockers: List<DecisionMessage> = emptyList(),
        warnings: List<DecisionMessage> = emptyList(),
        nextAction: CompatibilityNextAction,
    ) = CompatibilityDecision(
        decisionStatus = status,
        validationLevel = profile.validationLevel,
        googleEnvironmentAssessment = profile.googleEnvironmentAssessment,
        freeMvpCoverage = freeMvpCoverage,
        applicableWorkflow = workflow,
        confidence = confidence,
        evidence = evidence,
        blockers = blockers,
        warnings = warnings,
        nextAction = nextAction,
        catalogVersion = catalogSnapshot?.catalogVersion,
        catalogDigest = catalogSnapshot?.catalogDigest,
    )

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

    private fun GoogleEnvironmentAssessment.isNoActionRequired(
        validationLevel: GlobalValidationLevel,
    ): Boolean = componentSetState == GoogleComponentSetState.COMPLETE &&
        componentTrust == ComponentTrust.TRUSTED &&
        (
            functionalHealth == FunctionalHealth.VERIFIED_HEALTHY ||
                functionalHealth == FunctionalHealth.USER_CONFIRMED &&
                validationLevel == GlobalValidationLevel.DEVICE_VERIFIED
            )

    private companion object {
        val DIAGNOSTIC_STATES = setOf(
            GoogleComponentSetState.COMPLETE,
            GoogleComponentSetState.PARTIAL,
            GoogleComponentSetState.ABSENT,
        )
    }
}
