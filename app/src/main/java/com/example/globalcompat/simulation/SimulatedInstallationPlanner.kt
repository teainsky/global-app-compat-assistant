package com.example.globalcompat.simulation

import com.example.globalcompat.baseline.OfficialComponentComparison
import com.example.globalcompat.baseline.OfficialComponentMatchStatus
import com.example.globalcompat.catalog.ArtifactIntegrityStatus
import com.example.globalcompat.catalog.ArtifactSourceRecord
import com.example.globalcompat.catalog.ArtifactVerificationReadiness
import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.CatalogMatchRequest
import com.example.globalcompat.catalog.CatalogSystemFamily
import com.example.globalcompat.catalog.ComponentArtifact
import com.example.globalcompat.catalog.ComponentCatalog
import com.example.globalcompat.catalog.ComponentRelease
import com.example.globalcompat.catalog.InstalledArtifactSignatureStatus
import com.example.globalcompat.catalog.SourceAvailabilityStatus
import com.example.globalcompat.catalog.TrustedComponentCatalogMatcher
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.data.EnvironmentReport
import com.example.globalcompat.data.RomFamily

class SimulatedInstallationPlanner(
    private val catalog: ComponentCatalog = BuiltInComponentCatalog.catalog,
    private val catalogMatcher: TrustedComponentCatalogMatcher = TrustedComponentCatalogMatcher(),
) {
    fun create(
        environment: EnvironmentReport,
        comparisons: List<OfficialComponentComparison>,
    ): SimulatedInstallationPlan {
        val stages = mutableListOf(
            stage(
                SimulationFlowStage.DEVICE_DETECTION,
                SimulationStageStatus.PASS,
                "已读取设备、系统、ROM 与组件状态。",
            ),
        )
        val plan = environment.compatibilityPlan

        if (plan.deviceCategory == DeviceCategory.HARMONYOS_5_PLUS ||
            environment.rom.family == RomFamily.HARMONY_OS_5_PLUS
        ) {
            stages += stage(
                SimulationFlowStage.PLAN_MATCHING,
                SimulationStageStatus.BLOCKED,
                "HarmonyOS 5+ 不适用 HarmonyOS 1–4 的兼容方案。",
            )
            return result(
                environment = environment,
                status = SimulationPlanStatus.BLOCKED,
                nextAction = SimulationNextAction.STOP_UNSUPPORTED_SYSTEM,
                stages = stages,
                nextActionMessage = "已停止：当前系统不进入旧鸿蒙流程。",
            )
        }

        stages += stage(
            SimulationFlowStage.PLAN_MATCHING,
            SimulationStageStatus.PASS,
            "设备分类为 ${plan.deviceCategory.name}，匹配方案 ${plan.planId.name}。",
        )

        if (plan.planId == CompatibilityPlanId.NO_ACTION_REQUIRED) {
            return result(
                environment = environment,
                status = SimulationPlanStatus.NO_ACTION_REQUIRED,
                nextAction = SimulationNextAction.NO_ACTION_REQUIRED,
                stages = stages,
                nextActionMessage = "现有 Google 环境无需处理。",
            )
        }

        if (plan.planId != CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN ||
            plan.deviceCategory != DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT ||
            environment.rom.family != RomFamily.HARMONY_OS
        ) {
            stages += stage(
                SimulationFlowStage.OFFICIAL_COMPONENT_SELECTION,
                SimulationStageStatus.BLOCKED,
                "当前 catalog 仅支持 Huawei HarmonyOS 1–4 的官方 -hw 两件套。",
            )
            return result(
                environment = environment,
                status = SimulationPlanStatus.BLOCKED,
                nextAction = SimulationNextAction.STOP_INSUFFICIENT_EVIDENCE,
                stages = stages,
                nextActionMessage = "已停止：没有可安全使用的 catalog 规则。",
            )
        }

        val selection = catalogMatcher.select(
            catalog = catalog,
            request = CatalogMatchRequest(
                planId = plan.planId,
                deviceCategory = plan.deviceCategory,
                deviceFamily = environment.device.model,
                systemFamily = CatalogSystemFamily.HUAWEI_HARMONY_OS,
                systemVersion = environment.rom.version.orEmpty(),
                androidApiLevel = environment.android.apiLevel,
            ),
        )
        val release = selection.compatibleReleases.singleOrNull()
        val orderedArtifacts = release?.orderedHuaweiPair(plan.installationOrder).orEmpty()
        if (release == null || orderedArtifacts.size != REQUIRED_COMPONENT_IDS.size) {
            stages += stage(
                SimulationFlowStage.OFFICIAL_COMPONENT_SELECTION,
                SimulationStageStatus.BLOCKED,
                "未找到唯一且元数据完整的官方 Huawei 两件套。",
            )
            return result(
                environment = environment,
                status = SimulationPlanStatus.BLOCKED,
                nextAction = SimulationNextAction.STOP_OFFICIAL_COMPONENT_UNAVAILABLE,
                stages = stages,
                nextActionMessage = "已安全停止：官方组件不可取得或选择不唯一。",
            )
        }

        val sourceRecords = orderedArtifacts.map { artifact ->
            artifact to catalog.exactAvailableSources(artifact)
        }
        if (sourceRecords.any { (_, records) -> records.size != 1 }) {
            stages += stage(
                SimulationFlowStage.OFFICIAL_COMPONENT_SELECTION,
                SimulationStageStatus.BLOCKED,
                "至少一个组件没有唯一、精确匹配的官方可用来源。",
            )
            return result(
                environment = environment,
                status = SimulationPlanStatus.BLOCKED,
                nextAction = SimulationNextAction.STOP_OFFICIAL_COMPONENT_UNAVAILABLE,
                stages = stages,
                nextActionMessage = "已安全停止：不会猜测文件名、地址或第三方来源。",
            )
        }

        val selectedArtifacts = sourceRecords.map { (artifact, records) ->
            artifact.toSimulatedArtifact(records.single())
        }
        stages += stage(
            SimulationFlowStage.OFFICIAL_COMPONENT_SELECTION,
            SimulationStageStatus.PASS,
            "已选择 ${release.releaseTag} 的官方 Huawei -hw 两件套。",
        )

        val integrityFailures = sourceRecords.filterNot { (artifact, records) ->
            artifact.hasTrustedIntegrityEvidence(records.single())
        }
        if (integrityFailures.isNotEmpty()) {
            stages += stage(
                SimulationFlowStage.INTEGRITY_CHECK,
                SimulationStageStatus.BLOCKED,
                "catalog 的 SHA-256、签名或官方 source digest 证据不完整。",
            )
            return result(
                environment = environment,
                status = SimulationPlanStatus.BLOCKED,
                nextAction = SimulationNextAction.STOP_INTEGRITY_EVIDENCE_INCOMPLETE,
                stages = stages,
                nextActionMessage = "已安全停止：完整性证据未达到要求。",
                release = release,
                selectedArtifacts = selectedArtifacts,
            )
        }
        stages += stage(
            SimulationFlowStage.INTEGRITY_CHECK,
            SimulationStageStatus.PASS,
            "两件套 catalog 证据均达到 SIGNATURE_VERIFIED；这不提升设备兼容状态。",
        )

        val comparisonByPackage = comparisons.associateBy { it.fingerprint.packageName }
        val currentComponents = orderedArtifacts.map { artifact ->
            artifact.toCurrentDecision(comparisonByPackage[artifact.packageName])
        }
        if (currentComponents.any { it.state == CurrentComponentState.SIGNATURE_MISMATCH }) {
            stages += stage(
                SimulationFlowStage.CURRENT_COMPONENT_ASSESSMENT,
                SimulationStageStatus.BLOCKED,
                "检测到已安装组件签名异常。",
            )
            return result(
                environment = environment,
                status = SimulationPlanStatus.BLOCKED,
                nextAction = SimulationNextAction.STOP_SIGNATURE_MISMATCH,
                stages = stages,
                nextActionMessage = "已安全停止：签名异常时不计算替换或安装动作。",
                release = release,
                selectedArtifacts = selectedArtifacts,
                currentComponents = currentComponents,
            )
        }
        if (currentComponents.any {
                it.state == CurrentComponentState.UNREADABLE ||
                    it.state == CurrentComponentState.UNKNOWN
            }
        ) {
            stages += stage(
                SimulationFlowStage.CURRENT_COMPONENT_ASSESSMENT,
                SimulationStageStatus.BLOCKED,
                "至少一个已安装组件状态无法安全读取。",
            )
            return result(
                environment = environment,
                status = SimulationPlanStatus.BLOCKED,
                nextAction = SimulationNextAction.STOP_INSUFFICIENT_EVIDENCE,
                stages = stages,
                nextActionMessage = "已安全停止：组件证据不足。",
                release = release,
                selectedArtifacts = selectedArtifacts,
                currentComponents = currentComponents,
            )
        }

        val needsArtifactVerification = currentComponents.any {
            it.state == CurrentComponentState.COMPATIBILITY_SIGNATURE_REPORTED
        }
        stages += stage(
            SimulationFlowStage.CURRENT_COMPONENT_ASSESSMENT,
            if (needsArtifactVerification) SimulationStageStatus.REVIEW else SimulationStageStatus.PASS,
            if (needsArtifactVerification) {
                "系统报告了兼容签名；仍需主机端 APK 字节审计才能证明原文件一致。"
            } else {
                "已完成当前两组件状态判断。"
            },
        )

        val installSteps = currentComponents.mapNotNull { decision ->
            val artifact = orderedArtifacts.single { it.componentId == decision.componentId }
            when (decision.state) {
                CurrentComponentState.NOT_INSTALLED -> artifact.toStep(SimulatedInstallAction.INSTALL)
                CurrentComponentState.VERSION_MISMATCH ->
                    artifact.toStep(SimulatedInstallAction.REPLACE_VERSION)
                else -> null
            }
        }.mapIndexed { index, step -> step.copy(order = index + 1) }

        if (installSteps.isEmpty() && currentComponents.all {
                it.state == CurrentComponentState.OFFICIAL_ARTIFACT_MATCH
            }
        ) {
            stages += stage(
                SimulationFlowStage.INSTALLATION_ORDER,
                SimulationStageStatus.SKIPPED,
                "两件套均与已审计官方 artifact 完全一致，无需安装动作。",
            )
            return result(
                environment = environment,
                status = SimulationPlanStatus.NO_ACTION_REQUIRED,
                nextAction = SimulationNextAction.NO_ACTION_REQUIRED,
                stages = stages,
                nextActionMessage = "已经装好，不需要处理。",
                release = release,
                selectedArtifacts = selectedArtifacts,
                currentComponents = currentComponents,
            )
        }

        stages += stage(
            SimulationFlowStage.INSTALLATION_ORDER,
            if (needsArtifactVerification) SimulationStageStatus.REVIEW else SimulationStageStatus.PASS,
            if (installSteps.isEmpty()) {
                "当前没有安装步骤；需先完成现有 APK 原文件验证。"
            } else {
                "已按 GmsCore → Companion 依赖顺序生成 ${installSteps.size} 个模拟步骤。"
            },
        )
        return if (needsArtifactVerification) {
            result(
                environment = environment,
                status = SimulationPlanStatus.REVIEW_REQUIRED,
                nextAction = SimulationNextAction.VERIFY_CURRENT_ARTIFACT,
                stages = stages,
                nextActionMessage = "下一步：先完成现有组件只读字节审计。",
                release = release,
                selectedArtifacts = selectedArtifacts,
                currentComponents = currentComponents,
                installSteps = installSteps,
            )
        } else {
            result(
                environment = environment,
                status = SimulationPlanStatus.SIMULATION_READY,
                nextAction = SimulationNextAction.REVIEW_SIMULATED_STEPS,
                stages = stages,
                nextActionMessage = "模拟计划已生成；当前不会下载或安装。",
                release = release,
                selectedArtifacts = selectedArtifacts,
                currentComponents = currentComponents,
                installSteps = installSteps,
            )
        }
    }

    private fun ComponentRelease.orderedHuaweiPair(
        planOrder: List<String>,
    ): List<ComponentArtifact> {
        val byId = artifacts.associateBy { it.componentId }
        val order = planOrder.takeIf { it.toSet() == REQUIRED_COMPONENT_IDS }
            ?: DEFAULT_COMPONENT_ORDER
        return order.mapNotNull(byId::get)
    }

    private fun ComponentCatalog.exactAvailableSources(
        artifact: ComponentArtifact,
    ): List<ArtifactSourceRecord> = sourceRecords.filter { source ->
        source.componentId == artifact.componentId &&
            source.sourceType == artifact.sourceType &&
            source.sourceType in verificationPolicy.allowedSourceTypes &&
            source.availabilityStatus == SourceAvailabilityStatus.AVAILABLE &&
            source.sourceAssetId == artifact.githubAssetId &&
            source.observedFilename == artifact.artifactFilename &&
            !source.downloadUrl.isNullOrBlank()
    }

    private fun ComponentArtifact.hasTrustedIntegrityEvidence(
        source: ArtifactSourceRecord,
    ): Boolean {
        val assessment = catalogMatcher.assessVerification(catalog.verificationPolicy, this)
        val catalogSha256 = sha256?.normalizeDigest() ?: return false
        val catalogSigner = signingCertificateDigest?.normalizeDigest() ?: return false
        val sourceSha256 = source.sourceDigest
            ?.substringAfter("sha256:", missingDelimiterValue = "")
            ?.normalizeDigest()
        return integrityStatus == ArtifactIntegrityStatus.SIGNATURE_VERIFIED &&
            catalogSha256.length == SHA256_HEX_LENGTH &&
            catalogSigner.length == SHA256_HEX_LENGTH &&
            sourceSha256 == catalogSha256 &&
            assessment.readiness == ArtifactVerificationReadiness.READY_FOR_DOWNLOAD_VERIFICATION
    }

    private fun ComponentArtifact.toSimulatedArtifact(
        source: ArtifactSourceRecord,
    ) = SimulatedArtifact(
        componentId = componentId,
        packageName = packageName,
        artifactFilename = requireNotNull(artifactFilename),
        versionCode = requireNotNull(artifactVersionCode),
        versionName = requireNotNull(artifactVersionName),
        sourceType = source.sourceType,
        sourceAvailability = source.availabilityStatus,
        integrityStatus = integrityStatus,
        compatibilityStatus = compatibilityStatus,
    )

    private fun ComponentArtifact.toCurrentDecision(
        comparison: OfficialComponentComparison?,
    ): CurrentComponentDecision {
        val state = when {
            comparison == null -> CurrentComponentState.UNKNOWN
            comparison.signatureStatus == InstalledArtifactSignatureStatus.SIGNER_MISMATCH ->
                CurrentComponentState.SIGNATURE_MISMATCH
            comparison.status == OfficialComponentMatchStatus.NOT_INSTALLED ->
                CurrentComponentState.NOT_INSTALLED
            comparison.status == OfficialComponentMatchStatus.VERSION_MISMATCH ->
                CurrentComponentState.VERSION_MISMATCH
            comparison.status == OfficialComponentMatchStatus.UNREADABLE ->
                CurrentComponentState.UNREADABLE
            comparison.status == OfficialComponentMatchStatus.UNKNOWN ->
                CurrentComponentState.UNKNOWN
            comparison.status == OfficialComponentMatchStatus.VERSION_MATCH &&
                comparison.signatureStatus ==
                InstalledArtifactSignatureStatus.ACTUAL_ARTIFACT_MATCH ->
                CurrentComponentState.OFFICIAL_ARTIFACT_MATCH
            comparison.status == OfficialComponentMatchStatus.VERSION_MATCH &&
                comparison.signatureStatus ==
                InstalledArtifactSignatureStatus.COMPATIBILITY_SIGNATURE_REPORTED ->
                CurrentComponentState.COMPATIBILITY_SIGNATURE_REPORTED
            else -> CurrentComponentState.UNKNOWN
        }
        val detail = when (state) {
            CurrentComponentState.OFFICIAL_ARTIFACT_MATCH -> "APK 字节与官方审计 artifact 完全一致"
            CurrentComponentState.COMPATIBILITY_SIGNATURE_REPORTED -> "版本一致，系统报告兼容签名"
            CurrentComponentState.NOT_INSTALLED -> "当前未安装"
            CurrentComponentState.VERSION_MISMATCH -> "已安装版本与候选 artifact 不一致"
            CurrentComponentState.SIGNATURE_MISMATCH -> "系统报告的签名不符合已知规则"
            CurrentComponentState.UNREADABLE -> "PackageManager 无法读取"
            CurrentComponentState.UNKNOWN -> "证据不足，无法安全判断"
        }
        return CurrentComponentDecision(
            componentId = componentId,
            packageName = packageName,
            state = state,
            detail = detail,
        )
    }

    private fun ComponentArtifact.toStep(
        action: SimulatedInstallAction,
    ) = SimulatedInstallationStep(
        order = 0,
        componentId = componentId,
        packageName = packageName,
        artifactFilename = requireNotNull(artifactFilename),
        action = action,
    )

    private fun result(
        environment: EnvironmentReport,
        status: SimulationPlanStatus,
        nextAction: SimulationNextAction,
        stages: List<SimulationStageResult>,
        nextActionMessage: String,
        release: ComponentRelease? = null,
        selectedArtifacts: List<SimulatedArtifact> = emptyList(),
        currentComponents: List<CurrentComponentDecision> = emptyList(),
        installSteps: List<SimulatedInstallationStep> = emptyList(),
    ) = SimulatedInstallationPlan(
        schemaVersion = 1,
        simulationOnly = true,
        realInstallationAllowed = false,
        status = status,
        deviceCategory = environment.compatibilityPlan.deviceCategory,
        compatibilityPlanId = environment.compatibilityPlan.planId,
        deviceModel = environment.device.model,
        systemVersion = environment.rom.version.orEmpty(),
        androidApiLevel = environment.android.apiLevel,
        validationLevel = environment.deviceProfile.validationLevel,
        selectedReleaseTag = release?.releaseTag,
        selectedArtifacts = selectedArtifacts,
        currentComponents = currentComponents,
        installationOrder = installSteps,
        nextAction = nextAction,
        stages = finalizeStages(stages, nextActionMessage),
        warnings = buildList {
            add("仅模拟流程；不会下载、安装、卸载或修改系统。")
            if (selectedArtifacts.any {
                    it.compatibilityStatus !=
                        com.example.globalcompat.catalog.CompatibilityValidationStatus.DEVICE_VERIFIED
                }
            ) {
                add("候选组件仍为 CANDIDATE/UNTESTED，不具备真实安装资格。")
            }
        },
    )

    private fun finalizeStages(
        stages: List<SimulationStageResult>,
        nextActionMessage: String,
    ): List<SimulationStageResult> {
        val byStage = stages.associateBy { it.stage }
        return SimulationFlowStage.entries.map { flowStage ->
            byStage[flowStage] ?: if (flowStage == SimulationFlowStage.NEXT_ACTION) {
                stage(
                    flowStage,
                    if (stages.any { it.status == SimulationStageStatus.BLOCKED }) {
                        SimulationStageStatus.BLOCKED
                    } else {
                        SimulationStageStatus.PASS
                    },
                    nextActionMessage,
                )
            } else {
                stage(flowStage, SimulationStageStatus.SKIPPED, "前置条件未满足，未执行。")
            }
        }
    }

    private fun stage(
        stage: SimulationFlowStage,
        status: SimulationStageStatus,
        message: String,
    ) = SimulationStageResult(stage, status, message)

    private fun String.normalizeDigest(): String = replace(":", "").lowercase()

    private companion object {
        const val SHA256_HEX_LENGTH = 64
        val DEFAULT_COMPONENT_ORDER = listOf(
            "microg_services_huawei_compatible",
            "microg_companion_huawei_compatible",
        )
        val REQUIRED_COMPONENT_IDS = DEFAULT_COMPONENT_ORDER.toSet()
    }
}
