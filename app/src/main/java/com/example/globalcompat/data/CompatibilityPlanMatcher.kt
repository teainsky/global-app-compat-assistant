package com.example.globalcompat.data

fun interface CompatibilityPlanMatcher {
    fun match(context: CompatibilityContext): CompatibilityPlan
}

class RuleBasedCompatibilityPlanMatcher : CompatibilityPlanMatcher {
    override fun match(context: CompatibilityContext): CompatibilityPlan {
        val baseEvidence = buildList {
            add(
                PlanEvidence(
                    code = "DEVICE_IDENTITY",
                    source = "Build",
                    observedValue = listOf(
                        context.device.manufacturer,
                        context.device.brand,
                        context.device.model,
                    ).filter(String::isNotBlank).joinToString(" / "),
                    description = "设备制造商、品牌与型号",
                ),
            )
            add(
                PlanEvidence(
                    code = "ROM_IDENTIFICATION",
                    source = "rom.family",
                    observedValue = listOfNotNull(
                        context.rom.displayName,
                        context.rom.version,
                    ).joinToString(" "),
                    description = "Task 001 ROM 识别结果",
                ),
            )
            addAll(context.components.map(::componentEvidence))
        }

        if (isHarmonyOs5Plus(context.rom)) {
            return plan(
                category = DeviceCategory.HARMONYOS_5_PLUS,
                planId = CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN,
                status = CompatibilityPlanStatus.UNSUPPORTED,
                evidence = baseEvidence,
                warnings = listOf(
                    warning(
                        CompatibilityWarningCode.INSTALL_WORKFLOW_NOT_APPLICABLE,
                        "HarmonyOS 5+ 不进入 HarmonyOS 1–4 的 Android 兼容安装流程。",
                    ),
                ),
                confidence = context.rom.confidence,
            )
        }

        if (context.components.any { it.presence == ComponentPresence.CHECK_FAILED }) {
            return plan(
                category = DeviceCategory.UNKNOWN,
                planId = CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN,
                status = CompatibilityPlanStatus.UNSUPPORTED,
                evidence = baseEvidence,
                warnings = listOf(
                    warning(
                        CompatibilityWarningCode.COMPONENT_SCAN_INCOMPLETE,
                        "至少一个关键组件检测失败，当前证据不足以安全推荐方案。",
                    ),
                ),
                confidence = DetectionConfidence.UNKNOWN,
            )
        }

        val playServices = context.components.component(ComponentId.GOOGLE_PLAY_SERVICES)
        val playStore = context.components.component(ComponentId.GOOGLE_PLAY_STORE)
        val hasPlayServices = playServices.isUsable()
        val hasPlayStore = playStore.isUsable()

        if (isHuaweiHarmonyAndroidCompat(context)) {
            val partialWarning = if (hasPlayServices || hasPlayStore) {
                listOf(
                    warning(
                        CompatibilityWarningCode.PARTIAL_GOOGLE_COMPONENTS,
                        "检测到部分 Google 包；其来源、签名与运行状态未验证，不能视为完整环境。",
                    ),
                )
            } else {
                emptyList()
            }
            return plan(
                category = DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT,
                planId = CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN,
                status = CompatibilityPlanStatus.CONFIGURATION_REQUIRED,
                requiredComponents = HUAWEI_REQUIRED_COMPONENTS,
                installationOrder = HUAWEI_REQUIRED_COMPONENTS.map { it.componentId },
                evidence = baseEvidence,
                warnings = partialWarning + listOf(
                    warning(
                        CompatibilityWarningCode.MICROG_RUNTIME_NOT_ASSESSED,
                        "仅输出 Huawei-compatible 兼容环境需求，不判断 microG 当前是否存在或工作正常。",
                    ),
                    warning(
                        CompatibilityWarningCode.VERSION_POLICY_NOT_CONFIGURED,
                        "组件版本必须由后续规则数据库按设备与系统版本验证，当前不绑定最新版。",
                    ),
                ),
                confidence = DetectionConfidence.HIGH,
            )
        }

        if (hasPlayServices && hasPlayStore) {
            return plan(
                category = DeviceCategory.STANDARD_GMS,
                planId = CompatibilityPlanId.NO_ACTION_REQUIRED,
                status = CompatibilityPlanStatus.READY,
                evidence = baseEvidence,
                warnings = listOf(
                    warning(
                        CompatibilityWarningCode.COMPONENT_HEALTH_NOT_VERIFIED,
                        "仅确认 Play Services 与 Play Store 包存在且启用；未验证签名、运行健康、Play 认证或账号登录。",
                    ),
                ),
                confidence = DetectionConfidence.MEDIUM,
            )
        }

        if (hasPlayServices || hasPlayStore || playServices.isPresentButDisabled() || playStore.isPresentButDisabled()) {
            val missingComponents = missingGoogleComponents(playServices, playStore)
            return plan(
                category = DeviceCategory.PARTIAL_GMS,
                planId = CompatibilityPlanId.GMS_REPAIR_REQUIRED,
                status = CompatibilityPlanStatus.REPAIR_REQUIRED,
                requiredComponents = missingComponents,
                installationOrder = missingComponents.map { it.componentId },
                evidence = baseEvidence,
                warnings = listOf(
                    warning(
                        CompatibilityWarningCode.GOOGLE_COMPONENT_SET_INCOMPLETE,
                        "Google 核心组件不完整或未启用；不能根据单个包判断环境完整。",
                    ),
                ),
                confidence = DetectionConfidence.HIGH,
            )
        }

        if (context.rom.family in CHINA_ANDROID_ROM_FAMILIES) {
            return plan(
                category = DeviceCategory.CHINA_ANDROID_NO_GMS,
                planId = CompatibilityPlanId.GMS_REPAIR_REQUIRED,
                status = CompatibilityPlanStatus.REPAIR_REQUIRED,
                requiredComponents = GOOGLE_REQUIRED_COMPONENTS,
                installationOrder = GOOGLE_REQUIRED_COMPONENTS.map { it.componentId },
                evidence = baseEvidence,
                warnings = listOf(
                    warning(
                        CompatibilityWarningCode.DEVICE_RULE_NOT_VERIFIED,
                        "当前仅确认关键 Google 包缺失；具体可用方案仍需设备与 ROM 规则验证。",
                    ),
                    warning(
                        CompatibilityWarningCode.HMS_NOT_GMS_SIGNAL,
                        "HMS Core 是否存在不代表 Google 环境可用或不可用。",
                    ),
                ),
                confidence = DetectionConfidence.MEDIUM,
            )
        }

        return plan(
            category = DeviceCategory.UNKNOWN,
            planId = CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN,
            status = CompatibilityPlanStatus.UNSUPPORTED,
            evidence = baseEvidence,
            warnings = listOf(
                warning(
                    CompatibilityWarningCode.INSUFFICIENT_EVIDENCE,
                    "ROM 或设备证据不足，未强行推荐 Google 环境方案。",
                ),
            ),
            confidence = DetectionConfidence.UNKNOWN,
        )
    }

    private fun isHarmonyOs5Plus(rom: RomIdentification): Boolean {
        if (rom.family == RomFamily.HARMONY_OS_5_PLUS) return true
        if (rom.family != RomFamily.HARMONY_OS) return false
        return rom.version.majorVersion()?.let { it >= HARMONY_5_PLUS_MAJOR_VERSION } == true
    }

    private fun isHuaweiHarmonyAndroidCompat(context: CompatibilityContext): Boolean {
        if (context.rom.family != RomFamily.HARMONY_OS) return false
        val isHuawei = context.device.manufacturer.equals("HUAWEI", ignoreCase = true) ||
            context.device.brand.equals("HUAWEI", ignoreCase = true)
        val majorVersion = context.rom.version.majorVersion() ?: return false
        return isHuawei && majorVersion in 1..HARMONY_ANDROID_COMPAT_MAX_MAJOR_VERSION
    }

    private fun String?.majorVersion(): Int? = this
        ?.let { VERSION_NUMBER.find(it)?.value }
        ?.toIntOrNull()

    private fun List<SystemComponent>.component(id: ComponentId): SystemComponent? =
        firstOrNull { it.id == id }

    private fun SystemComponent?.isUsable(): Boolean =
        this?.presence == ComponentPresence.PRESENT && this.enabled == true

    private fun SystemComponent?.isPresentButDisabled(): Boolean =
        this?.presence == ComponentPresence.PRESENT && this.enabled != true

    private fun componentEvidence(component: SystemComponent) = PlanEvidence(
        code = "COMPONENT_${component.id.name}",
        source = component.packageName,
        observedValue = buildString {
            append(component.presence.name)
            append(", enabled=")
            append(component.enabled ?: "unknown")
            component.versionName?.let {
                append(", version=")
                append(it)
            }
        },
        description = component.displayName,
    )

    private fun missingGoogleComponents(
        playServices: SystemComponent?,
        playStore: SystemComponent?,
    ): List<RequiredCompatibilityComponent> = buildList {
        if (!playServices.isUsable()) add(GOOGLE_PLAY_SERVICES_REQUIREMENT)
        if (!playStore.isUsable()) add(GOOGLE_PLAY_STORE_REQUIREMENT)
    }

    private fun plan(
        category: DeviceCategory,
        planId: CompatibilityPlanId,
        status: CompatibilityPlanStatus,
        requiredComponents: List<RequiredCompatibilityComponent> = emptyList(),
        installationOrder: List<String> = emptyList(),
        evidence: List<PlanEvidence>,
        warnings: List<CompatibilityWarning>,
        confidence: DetectionConfidence,
    ) = CompatibilityPlan(
        deviceCategory = category,
        planId = planId,
        status = status,
        requiredComponents = requiredComponents,
        installationOrder = installationOrder,
        evidence = evidence,
        warnings = warnings,
        confidence = confidence,
    )

    private fun warning(
        code: CompatibilityWarningCode,
        message: String,
    ) = CompatibilityWarning(code = code, message = message)

    private companion object {
        const val HARMONY_ANDROID_COMPAT_MAX_MAJOR_VERSION = 4
        const val HARMONY_5_PLUS_MAJOR_VERSION = 5
        val VERSION_NUMBER = Regex("\\d+")

        val CHINA_ANDROID_ROM_FAMILIES = setOf(
            RomFamily.HYPER_OS,
            RomFamily.COLOR_OS,
            RomFamily.ORIGIN_OS,
            RomFamily.MAGIC_OS,
        )

        val GOOGLE_PLAY_SERVICES_REQUIREMENT = RequiredCompatibilityComponent(
            componentId = "google_play_services",
            displayName = "Google Play Services",
        )
        val GOOGLE_PLAY_STORE_REQUIREMENT = RequiredCompatibilityComponent(
            componentId = "google_play_store",
            displayName = "Google Play Store",
        )
        val GOOGLE_REQUIRED_COMPONENTS = listOf(
            GOOGLE_PLAY_SERVICES_REQUIREMENT,
            GOOGLE_PLAY_STORE_REQUIREMENT,
        )
        val HUAWEI_REQUIRED_COMPONENTS = listOf(
            RequiredCompatibilityComponent(
                componentId = "microg_services_huawei_compatible",
                displayName = "microG Services Huawei-compatible variant",
            ),
            RequiredCompatibilityComponent(
                componentId = "microg_companion_huawei_compatible",
                displayName = "microG Companion Huawei-compatible variant",
            ),
        )
    }
}
