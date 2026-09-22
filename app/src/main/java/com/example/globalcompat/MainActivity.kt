package com.example.globalcompat

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.globalcompat.baseline.DeviceBaselineJsonExporter
import com.example.globalcompat.baseline.DeviceBaselineReportFactory
import com.example.globalcompat.baseline.DeviceBaselineScanResult
import com.example.globalcompat.baseline.DeviceBaselineScanner
import com.example.globalcompat.baseline.OfficialComponentComparison
import com.example.globalcompat.baseline.OfficialComponentMatchStatus
import com.example.globalcompat.baseline.UserFunctionalValidation
import com.example.globalcompat.baseline.UserValidationAnswer
import com.example.globalcompat.artifact.AndroidOnDeviceArtifactAuditService
import com.example.globalcompat.artifact.OnDeviceArtifactAuditAvailability
import com.example.globalcompat.artifact.OnDeviceArtifactAuditReport
import com.example.globalcompat.artifact.OnDeviceArtifactReadStatus
import com.example.globalcompat.catalog.InstalledArtifactSignatureStatus
import com.example.globalcompat.data.ComponentPresence
import com.example.globalcompat.data.CompatibilityPlan
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.CompatibilityDecision
import com.example.globalcompat.data.CompatibilityDecisionStatus
import com.example.globalcompat.data.SystemComponent
import com.example.globalcompat.installation.InstallationBlockReason
import com.example.globalcompat.installation.InstallationSessionPlan
import com.example.globalcompat.installation.InstallationSessionStatus
import com.example.globalcompat.installation.InstallationStepState
import com.example.globalcompat.preparation.AndroidEnvironmentPreparationService
import com.example.globalcompat.preparation.EnvironmentPreparationProgress
import com.example.globalcompat.preparation.EnvironmentPreparationRequest
import com.example.globalcompat.preparation.EnvironmentPreparationResult
import com.example.globalcompat.preparation.EnvironmentPreparationStage
import com.example.globalcompat.preparation.EnvironmentPreparationStatus
import com.example.globalcompat.preparation.PreparationCancellation
import com.example.globalcompat.simulation.CurrentComponentState
import com.example.globalcompat.simulation.SimulatedInstallAction
import com.example.globalcompat.simulation.SimulatedInstallationPlan
import com.example.globalcompat.simulation.SimulationFlowStage
import com.example.globalcompat.simulation.SimulationNextAction
import com.example.globalcompat.ui.theme.GlobalCompatTheme
import com.example.globalcompat.validation.ValidationDeviceProfile
import com.example.globalcompat.validation.ValidationSystemProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GlobalCompatTheme {
                ScannerScreen(
                    scanner = remember { DeviceBaselineScanner(applicationContext) },
                )
            }
        }
    }
}
@Composable
private fun ScannerScreen(scanner: DeviceBaselineScanner) {
    var scanResult by remember { mutableStateOf<DeviceBaselineScanResult?>(null) }
    var functionalValidation by remember { mutableStateOf(UserFunctionalValidation()) }
    var pendingJson by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var preparationProgress by remember { mutableStateOf<EnvironmentPreparationProgress?>(null) }
    var preparationResult by remember { mutableStateOf<EnvironmentPreparationResult?>(null) }
    var activePreparation by remember { mutableStateOf<PreparationCancellation?>(null) }
    var artifactAuditReport by remember { mutableStateOf<OnDeviceArtifactAuditReport?>(null) }
    var isAuditingInstalledArtifacts by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reportFactory = remember { DeviceBaselineReportFactory() }
    val preparationService = remember(context.applicationContext) {
        AndroidEnvironmentPreparationService(context.applicationContext)
    }
    val artifactAuditService = remember(context.applicationContext) {
        AndroidOnDeviceArtifactAuditService(context.applicationContext)
    }
    DisposableEffect(Unit) {
        onDispose { activePreparation?.cancel() }
    }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = pendingJson
        pendingJson = null
        if (uri != null && json != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "w")
                            ?.bufferedWriter(Charsets.UTF_8)
                            ?.use { writer -> writer.write(json) }
                            ?: error("无法打开目标文件")
                    }.isSuccess
                }
                Toast.makeText(
                    context,
                    if (saved) "兼容验证报告已保存" else "报告保存失败",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Scaffold { innerPadding ->
        when {
            isScanning -> LoadingState(Modifier.padding(innerPadding))
            scanResult == null -> StartState(
                modifier = Modifier.padding(innerPadding),
                onStart = {
                    activePreparation?.cancel()
                    activePreparation = null
                    preparationProgress = null
                    preparationResult = null
                    artifactAuditReport = null
                    isAuditingInstalledArtifacts = false
                    isScanning = true
                    scope.launch {
                        scanResult = withContext(Dispatchers.IO) { scanner.scan() }
                        functionalValidation = UserFunctionalValidation()
                        isScanning = false
                    }
                },
            )
            else -> EnvironmentReportView(
                scanResult = checkNotNull(scanResult),
                functionalValidation = functionalValidation,
                onFunctionalValidationChange = { functionalValidation = it },
                preparationProgress = preparationProgress,
                preparationResult = preparationResult,
                onPrepareEnvironment = {
                    val result = checkNotNull(scanResult)
                    val cancellation = PreparationCancellation()
                    activePreparation = cancellation
                    preparationResult = null
                    preparationProgress = EnvironmentPreparationProgress(
                        status = EnvironmentPreparationStatus.PREPARING,
                        stage = EnvironmentPreparationStage.WAITING,
                        componentIndex = 0,
                        componentCount = 2,
                        currentComponent = null,
                        downloadedBytes = 0,
                        totalBytes = null,
                        userMessage = "正在准备 Google 运行环境",
                    )
                    scope.launch {
                        val completed = withContext(Dispatchers.IO) {
                            preparationService.prepare(
                                request = EnvironmentPreparationRequest(
                                    deviceCategory = result.environment.compatibilityPlan.deviceCategory,
                                    planId = result.environment.compatibilityPlan.planId,
                                    deviceModel = result.environment.device.model,
                                    systemVersion = result.environment.rom.version,
                                    androidApiLevel = result.environment.android.apiLevel,
                                    validationLevel = result.environment.deviceProfile.validationLevel,
                                    compatibilityDecisionStatus =
                                        result.environment.compatibilityDecision.decisionStatus,
                                ),
                                cancellation = cancellation,
                            ) { update ->
                                scope.launch { preparationProgress = update }
                            }
                        }
                        preparationResult = completed
                        activePreparation = null
                    }
                },
                onCancelPreparation = { activePreparation?.cancel() },
                artifactAuditReport = artifactAuditReport,
                isAuditingInstalledArtifacts = isAuditingInstalledArtifacts,
                onAuditInstalledArtifacts = {
                    val result = checkNotNull(scanResult)
                    isAuditingInstalledArtifacts = true
                    scope.launch {
                        val report = withContext(Dispatchers.IO) {
                            artifactAuditService.audit(
                                deviceProfile = ValidationDeviceProfile(
                                    manufacturer = result.environment.device.manufacturer,
                                    model = result.environment.device.model,
                                ),
                                systemProfile = ValidationSystemProfile(
                                    harmonyOsVersion = result.environment.rom.version,
                                    androidVersion = result.environment.android.release,
                                    androidApiLevel = result.environment.android.apiLevel,
                                    romFamily = result.environment.rom.family.name,
                                    romVersion = result.environment.rom.version,
                                ),
                            )
                        }
                        artifactAuditReport = report
                        scanResult = scanner.applyArtifactAudit(result, report)
                        isAuditingInstalledArtifacts = false
                    }
                },
                onExport = {
                    val result = checkNotNull(scanResult)
                    val baseline = reportFactory.create(
                        environment = result.environment,
                        comparisons = result.componentComparisons,
                        functionalValidation = functionalValidation,
                        capturedAtEpochMillis = result.environment.scannedAtEpochMillis,
                        artifactAuditReport = artifactAuditReport,
                    )
                    pendingJson = DeviceBaselineJsonExporter.toJson(baseline)
                    saveLauncher.launch("device-baseline.json")
                },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun StartState(
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "设备环境检测",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "读取本机系统与基础服务状态，不会安装或修改任何内容。",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onStart,
        ) {
            Text("开始检测")
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("正在读取本机环境…")
    }
}

@Composable
private fun EnvironmentReportView(
    scanResult: DeviceBaselineScanResult,
    functionalValidation: UserFunctionalValidation,
    onFunctionalValidationChange: (UserFunctionalValidation) -> Unit,
    preparationProgress: EnvironmentPreparationProgress?,
    preparationResult: EnvironmentPreparationResult?,
    onPrepareEnvironment: () -> Unit,
    onCancelPreparation: () -> Unit,
    artifactAuditReport: OnDeviceArtifactAuditReport?,
    isAuditingInstalledArtifacts: Boolean,
    onAuditInstalledArtifacts: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = scanResult.environment
    val validationRecordAvailable = remember(scanResult, functionalValidation) {
        DeviceBaselineReportFactory().create(
            environment = report,
            comparisons = scanResult.componentComparisons,
            functionalValidation = functionalValidation,
            capturedAtEpochMillis = report.scannedAtEpochMillis,
        ).deviceValidationRecord != null
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "本机环境报告",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            ReportSection("设备") {
                ReportRow("品牌", report.device.brand)
                ReportRow("制造商", report.device.manufacturer)
                ReportRow("型号", report.device.model)
                ReportRow("设备代号", report.device.device)
            }
        }
        item {
            ReportSection("Android") {
                ReportRow("API", report.android.apiLevel.toString())
                ReportRow("系统版本", report.android.release)
                ReportRow("安全补丁", report.android.securityPatch ?: "未知")
                ReportRow("构建版本", report.android.buildDisplay)
            }
        }
        item {
            ReportSection("ROM") {
                ReportRow("识别结果", report.rom.displayName)
                ReportRow("版本", report.rom.version ?: "未识别")
                ReportRow("置信度", report.rom.confidence.name)
            }
        }
        item {
            ReportSection("全球设备画像") {
                ReportRow("平台", report.deviceProfile.platformFamily.name)
                ReportRow("市场版本", report.deviceProfile.marketVariant.name)
                ReportRow(
                    "Google 组件集合",
                    report.deviceProfile.googleEnvironmentAssessment.componentSetState.name,
                )
                ReportRow(
                    "组件可信度",
                    report.deviceProfile.googleEnvironmentAssessment.componentTrust.name,
                )
                ReportRow(
                    "功能健康",
                    report.deviceProfile.googleEnvironmentAssessment.functionalHealth.name,
                )
                ReportRow(
                    "Play 认证",
                    report.deviceProfile.googleEnvironmentAssessment.playCertification.name,
                )
                ReportRow("安装能力", report.deviceProfile.installationCapability.name)
                ReportRow("验证等级", report.deviceProfile.validationLevel.name)
            }
        }
        item {
            Text(
                text = "基础组件",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(report.components, key = { it.id }) { component ->
            ComponentCard(component)
        }
        item {
            ReportSection("Google 兼容层") {
                ReportRow("状态", "暂未评估")
                Text(
                    text = report.googleCompatibilityLayer.note,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            CompatibilityDecisionCard(report.compatibilityDecision)
        }
        item {
            CompatibilityPlanCard(report.compatibilityPlan)
        }
        item {
            SimulatedInstallationPlanCard(scanResult.simulatedInstallationPlan)
        }
        item {
            EnvironmentPreparationCard(
                plan = report.compatibilityPlan,
                progress = preparationProgress,
                result = preparationResult,
                onPrepare = onPrepareEnvironment,
                onCancel = onCancelPreparation,
            )
        }
        item {
            InstallationExecutionGateCard(scanResult.installationSessionPlan)
        }
        item {
            Text(
                text = "官方组件指纹比对",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(scanResult.componentComparisons, key = { it.fingerprint.packageName }) { comparison ->
            ComponentFingerprintCard(comparison)
        }
        item {
            OnDeviceArtifactAuditCard(
                report = artifactAuditReport,
                isAuditing = isAuditingInstalledArtifacts,
                onAudit = onAuditInstalledArtifacts,
            )
        }
        item {
            UserValidationSection(
                validation = functionalValidation,
                onChange = onFunctionalValidationChange,
                validationRecordAvailable = validationRecordAvailable,
                onExport = onExport,
            )
        }
    }
}

@Composable
private fun OnDeviceArtifactAuditCard(
    report: OnDeviceArtifactAuditReport?,
    isAuditing: Boolean,
    onAudit: () -> Unit,
) {
    var showTechnicalDetails by remember(report) { mutableStateOf(false) }
    val statusMessage = when (report?.availability) {
        OnDeviceArtifactAuditAvailability.ON_DEVICE_ARTIFACT_AUDIT_SUPPORTED ->
            if (report.attainedEvidenceLevel != null) {
                "已读取本机组件原文件，并与官方审计记录一致"
            } else {
                "已读取本机组件原文件，但未完全匹配官方记录"
            }
        OnDeviceArtifactAuditAvailability.PARTIALLY_SUPPORTED ->
            "系统只允许读取部分组件，未生成完整原文件证据"
        OnDeviceArtifactAuditAvailability.NOT_ACCESSIBLE ->
            "系统不允许读取已安装组件原文件"
        null -> "无需连接电脑，尝试只读核对本机已安装组件原文件。"
    }
    ReportSection("本机原文件审计") {
        Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
        if (isAuditing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("正在读取并计算文件摘要…", style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onAudit,
            ) {
                Text(if (report == null) "开始只读审计" else "重新审计")
            }
        }
        report?.components?.forEach { component ->
            val componentName = when (component.packageName) {
                "com.google.android.gms" -> "服务组件"
                "com.android.vending" -> "配套组件"
                else -> "组件"
            }
            val resultText = when {
                component.officialArtifactMatched -> "官方原文件一致"
                component.readStatus == OnDeviceArtifactReadStatus.APK_FILE_NOT_READABLE ->
                    "原文件不可访问"
                component.readStatus == OnDeviceArtifactReadStatus.SPLIT_APK_LAYOUT_UNSUPPORTED ->
                    "检测到拆分安装，暂不能完整验证"
                component.readStatus == OnDeviceArtifactReadStatus.NOT_INSTALLED -> "未安装"
                else -> "未通过官方原文件核对"
            }
            Text("• $componentName：$resultText", style = MaterialTheme.typography.bodyMedium)
        }
        if (report?.attainedEvidenceLevel != null) {
            Text(
                "原文件证据等级：${report.attainedEvidenceLevel.name}；不会自动提升为 DEVICE_VERIFIED。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (report != null) {
            TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails }) {
                Text(if (showTechnicalDetails) "收起技术详情" else "查看技术详情")
            }
        }
        if (showTechnicalDetails) {
            report?.components?.forEach { component ->
                Text(
                    "${component.packageName} · ${component.readStatus.name}",
                    style = MaterialTheme.typography.bodySmall,
                )
                component.installedApkSha256?.let {
                    ReportRow("本机 APK SHA-256", it)
                }
                component.officialApkSha256?.let {
                    ReportRow("官方 APK SHA-256", it)
                }
                if (component.reportedSigningCertificateSha256.isNotEmpty()) {
                    ReportRow(
                        "系统报告签名",
                        component.reportedSigningCertificateSha256.joinToString(),
                    )
                }
                component.failureType?.let { ReportRow("读取失败类型", it) }
            }
        }
        Text(
            "该功能不申请新权限、不使用 Root/Shizuku，也不会修改已安装组件。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EnvironmentPreparationCard(
    plan: CompatibilityPlan,
    progress: EnvironmentPreparationProgress?,
    result: EnvironmentPreparationResult?,
    onPrepare: () -> Unit,
    onCancel: () -> Unit,
) {
    var showTechnicalDetails by remember(result, progress?.status) { mutableStateOf(false) }
    val isPreparing = progress?.status == EnvironmentPreparationStatus.PREPARING
    val isReady = result?.status == EnvironmentPreparationStatus.DOWNLOAD_VERIFIED_READY
    val branchAllowed = plan.deviceCategory ==
        com.example.globalcompat.data.DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT &&
        plan.planId == CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN
    val headline = when {
        isReady -> "环境文件已准备完成"
        result?.status == EnvironmentPreparationStatus.FAIL_CLOSED ->
            "准备失败，未保留无效文件"
        result?.status == EnvironmentPreparationStatus.CANCELLED -> "准备已取消"
        isPreparing -> progress.userMessage
        !branchAllowed -> "当前设备不适用此准备流程"
        else -> "准备 Google 运行环境"
    }

    ReportSection("准备环境文件") {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (isPreparing) {
            val total = progress.totalBytes
            val fraction = total?.takeIf { it > 0L }
                ?.let { (progress.downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (progress.componentIndex > 0) {
                Text(
                    text = "${progress.userMessage} · ${progress.currentComponent.userComponentName()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (progress.downloadedBytes > 0L) {
                val sizeText = total?.let {
                    "${progress.downloadedBytes.userFileSize()} / ${it.userFileSize()}"
                } ?: progress.downloadedBytes.userFileSize()
                Text(sizeText, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancel,
            ) {
                Text("取消")
            }
        } else {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = branchAllowed && !isReady,
                onClick = onPrepare,
            ) {
                Text(
                    when {
                        isReady -> "准备完成"
                        result?.status == EnvironmentPreparationStatus.FAIL_CLOSED -> "重新准备"
                        else -> "准备环境文件"
                    },
                )
            }
        }
        if (isReady && result?.installationAllowed == false) {
            Text(
                text = "文件已安全校验；当前方案尚未完成设备验证，安装仍然锁定。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!branchAllowed && plan.deviceCategory ==
            com.example.globalcompat.data.DeviceCategory.HARMONYOS_5_PLUS
        ) {
            Text(
                text = "HarmonyOS 5+ 不进入旧鸿蒙组件流程。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (result != null || progress?.technicalDetail != null) {
            TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails }) {
                Text(if (showTechnicalDetails) "收起详细信息" else "查看详细信息")
            }
        }
        if (showTechnicalDetails) {
            progress?.technicalDetail?.let { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            result?.failures?.forEach { failure ->
                Text("• ${failure.name}", style = MaterialTheme.typography.bodySmall)
            }
            result?.preparedComponents?.forEach { component ->
                Text(
                    "• ${component.artifactFilename} · ${component.sizeBytes.userFileSize()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            result?.technicalDetails?.forEach { detail ->
                Text("• $detail", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun String?.userComponentName(): String = when (this) {
    "microg_services_huawei_compatible" -> "服务组件"
    "microg_companion_huawei_compatible" -> "配套组件"
    else -> "组件"
}

private fun Long.userFileSize(): String = when {
    this >= 1024L * 1024L -> "%.1f MB".format(this / (1024f * 1024f))
    this >= 1024L -> "%.1f KB".format(this / 1024f)
    else -> "$this B"
}

@Composable
private fun CompatibilityDecisionCard(decision: CompatibilityDecision) {
    var showTechnicalEvidence by remember(decision) { mutableStateOf(false) }
    val userMessage = when (decision.decisionStatus) {
        CompatibilityDecisionStatus.NO_ACTION_REQUIRED -> "当前已验证组件状态无需处理"
        CompatibilityDecisionStatus.VERIFIED_WORKFLOW_AVAILABLE -> "已有可信方案，可以准备环境"
        CompatibilityDecisionStatus.DIAGNOSTIC_ONLY -> "检测到环境问题，当前仅提供诊断"
        CompatibilityDecisionStatus.CURRENT_WORKFLOW_NOT_APPLICABLE ->
            "当前工作流不适用于此系统"
        CompatibilityDecisionStatus.UNKNOWN -> "证据不足，暂时无法安全判断"
        CompatibilityDecisionStatus.BLOCKED_BY_KNOWN_RULE -> "可信规则已阻止当前流程"
    }

    ReportSection("全球兼容决策") {
        Text(
            text = userMessage,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        ReportRow("下一步", decision.nextAction.name)
        TextButton(onClick = { showTechnicalEvidence = !showTechnicalEvidence }) {
            Text(if (showTechnicalEvidence) "收起技术证据" else "查看技术证据")
        }
        if (showTechnicalEvidence) {
            ReportRow("决策", decision.decisionStatus.name)
            ReportRow("验证等级", decision.validationLevel.name)
            ReportRow(
                "Google 组件集合",
                decision.googleEnvironmentAssessment.componentSetState.name,
            )
            ReportRow(
                "组件可信度",
                decision.googleEnvironmentAssessment.componentTrust.name,
            )
            ReportRow(
                "功能健康",
                decision.googleEnvironmentAssessment.functionalHealth.name,
            )
            ReportRow(
                "Play 认证",
                decision.googleEnvironmentAssessment.playCertification.name,
            )
            ReportRow("适用工作流", decision.applicableWorkflow.name)
            ReportRow("置信度", decision.confidence.name)
            decision.evidence.forEach { evidence ->
                Text(
                    "• ${evidence.code}：${evidence.observedValue}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            (decision.blockers + decision.warnings).forEach { message ->
                Text(
                    "• ${message.code}：${message.message}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CompatibilityPlanCard(plan: CompatibilityPlan) {
    var showTechnicalEvidence by remember(plan) { mutableStateOf(false) }
    val userMessage = when (plan.planId) {
        CompatibilityPlanId.NO_ACTION_REQUIRED -> "当前已验证组件状态无需处理"
        CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN -> "当前设备需要配置兼容环境"
        CompatibilityPlanId.GMS_REPAIR_REQUIRED -> "Google 环境不完整，需要修复"
        CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN -> if (
            plan.status == com.example.globalcompat.data.CompatibilityPlanStatus.UNDETERMINED
        ) {
            "证据不足，需要进一步诊断"
        } else {
            "当前工作流不适用于此系统"
        }
    }

    ReportSection("推荐环境方案") {
        Text(
            text = userMessage,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = { showTechnicalEvidence = !showTechnicalEvidence }) {
            Text(if (showTechnicalEvidence) "收起技术证据" else "查看技术证据")
        }
        if (showTechnicalEvidence) {
            ReportRow("设备分类", plan.deviceCategory.name)
            ReportRow("方案", plan.planId.name)
            ReportRow("置信度", plan.confidence.name)
            if (plan.requiredComponents.isNotEmpty()) {
                Text("所需组件", style = MaterialTheme.typography.labelMedium)
                plan.requiredComponents.forEach { component ->
                    Text("• ${component.displayName}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (plan.evidence.isNotEmpty()) {
                Text("判断证据", style = MaterialTheme.typography.labelMedium)
                plan.evidence.forEach { evidence ->
                    Text(
                        text = "• ${evidence.description}：${evidence.observedValue}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (plan.warnings.isNotEmpty()) {
                Text("注意事项", style = MaterialTheme.typography.labelMedium)
                plan.warnings.forEach { warning ->
                    Text(
                        "• ${warning.code.name}：${warning.message}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallationExecutionGateCard(plan: InstallationSessionPlan) {
    var showTechnicalDetails by remember(plan) { mutableStateOf(false) }
    val buttonText = when {
        plan.status == InstallationSessionStatus.NO_ACTION_REQUIRED -> "无需安装"
        InstallationBlockReason.HARMONYOS_5_PLUS_NOT_SUPPORTED in plan.blockReasons ->
            "当前系统不适用，禁止安装"
        InstallationBlockReason.SIGNATURE_MISMATCH in plan.blockReasons ->
            "签名异常，禁止安装"
        InstallationBlockReason.OFFICIAL_SOURCE_UNAVAILABLE in plan.blockReasons ->
            "官方组件不可取得，暂不可安装"
        InstallationBlockReason.COMPATIBILITY_NOT_DEVICE_VERIFIED in plan.blockReasons ->
            "当前方案尚未完成设备验证，暂不可安装"
        InstallationBlockReason.DECISION_NOT_VERIFIED_WORKFLOW in plan.blockReasons ->
            "当前没有已验证可执行方案，暂不可安装"
        plan.status == InstallationSessionStatus.READY_FOR_USER_CONFIRMATION ->
            "安装接口尚未启用"
        else -> "安全门禁未通过，暂不可安装"
    }
    ReportSection("配置 Google 运行环境") {
        Text(
            text = if (plan.executionAllowed) {
                "正在配置 Google 运行环境"
            } else {
                plan.userMessage
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text("准备完成", style = MaterialTheme.typography.bodyMedium)
        Text("↓", style = MaterialTheme.typography.bodySmall)
        Text("安装必要组件 1/2", style = MaterialTheme.typography.bodyMedium)
        Text("↓", style = MaterialTheme.typography.bodySmall)
        Text("安装必要组件 2/2", style = MaterialTheme.typography.bodyMedium)
        Text("↓", style = MaterialTheme.typography.bodySmall)
        Text("检查环境", style = MaterialTheme.typography.bodyMedium)
        Text("↓", style = MaterialTheme.typography.bodySmall)
        Text("配置完成", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = false,
            onClick = {},
        ) {
            Text(buttonText)
        }
        Text(
            text = "当前设备尚未完成发布级验证，按钮保持锁定，不会触发系统安装界面。",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails }) {
            Text(if (showTechnicalDetails) "收起技术状态" else "查看技术状态")
        }
        if (showTechnicalDetails) {
            plan.steps.forEach { step ->
                val state = when (step.state) {
                    InstallationStepState.ALREADY_COMPLETED -> "已完成"
                    InstallationStepState.PENDING_DOWNLOAD -> "等待下载"
                    InstallationStepState.DOWNLOADED -> "已下载，待校验"
                    InstallationStepState.VERIFIED -> "校验通过"
                    InstallationStepState.READY_FOR_USER_CONFIRMATION -> "等待系统用户确认"
                    InstallationStepState.BLOCKED -> "已阻止"
                }
                Text(
                    text = "• ${step.packageName.ifBlank { step.componentId }}：$state",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            plan.blockReasons.forEach { reason ->
                Text("• ${reason.name}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SimulatedInstallationPlanCard(plan: SimulatedInstallationPlan) {
    var showTechnicalFlow by remember(plan) { mutableStateOf(false) }
    val userMessage = when (plan.nextAction) {
        SimulationNextAction.NO_ACTION_REQUIRED -> "已经装好，不需要处理"
        SimulationNextAction.REVIEW_SIMULATED_STEPS -> "模拟计划已生成"
        SimulationNextAction.VERIFY_CURRENT_ARTIFACT -> "需要先验证当前组件原文件"
        SimulationNextAction.STOP_SIGNATURE_MISMATCH -> "检测到签名异常，已停止"
        SimulationNextAction.STOP_UNSUPPORTED_SYSTEM -> "当前系统不适用此方案"
        SimulationNextAction.STOP_OFFICIAL_COMPONENT_UNAVAILABLE ->
            "官方组件当前不可取得，已安全停止"
        SimulationNextAction.STOP_INTEGRITY_EVIDENCE_INCOMPLETE ->
            "官方组件完整性证据不足，已安全停止"
        SimulationNextAction.STOP_INSUFFICIENT_EVIDENCE -> "证据不足，已安全停止"
    }

    ReportSection("模拟安装计划") {
        Text(
            text = userMessage,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "仅展示未来安装步骤；不会安装、卸载或修改系统。",
            style = MaterialTheme.typography.bodySmall,
        )
        plan.selectedReleaseTag?.let { ReportRow("官方候选版本", it) }
        if (plan.currentComponents.isNotEmpty()) {
            Text("当前组件状态", style = MaterialTheme.typography.labelMedium)
            plan.currentComponents.forEach { component ->
                val state = when (component.state) {
                    CurrentComponentState.OFFICIAL_ARTIFACT_MATCH -> "官方原文件匹配"
                    CurrentComponentState.COMPATIBILITY_SIGNATURE_REPORTED -> "系统报告兼容签名"
                    CurrentComponentState.NOT_INSTALLED -> "未安装"
                    CurrentComponentState.VERSION_MISMATCH -> "版本不匹配"
                    CurrentComponentState.SIGNATURE_MISMATCH -> "签名异常"
                    CurrentComponentState.UNREADABLE -> "无法读取"
                    CurrentComponentState.UNKNOWN -> "无法判断"
                }
                Text(
                    text = "• ${component.packageName}：$state",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (plan.installationOrder.isNotEmpty()) {
            Text("未来操作顺序（模拟）", style = MaterialTheme.typography.labelMedium)
            plan.installationOrder.forEach { step ->
                val action = when (step.action) {
                    SimulatedInstallAction.INSTALL -> "安装"
                    SimulatedInstallAction.REPLACE_VERSION -> "替换不匹配版本"
                }
                Text(
                    text = "${step.order}. $action ${step.artifactFilename}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        plan.warnings.forEach { warning ->
            Text("• $warning", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { showTechnicalFlow = !showTechnicalFlow }) {
            Text(if (showTechnicalFlow) "收起流程证据" else "查看完整流程")
        }
        if (showTechnicalFlow) {
            plan.stages.forEach { flow ->
                Text(
                    text = "${flow.stage.userLabel()} · ${flow.status.name}：${flow.message}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            plan.selectedArtifacts.forEach { artifact ->
                Text(
                    text = "${artifact.artifactFilename} · ${artifact.sourceAvailability.name} · " +
                        "${artifact.integrityStatus.name} · ${artifact.compatibilityStatus.name}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun SimulationFlowStage.userLabel(): String = when (this) {
    SimulationFlowStage.DEVICE_DETECTION -> "检测设备"
    SimulationFlowStage.PLAN_MATCHING -> "匹配方案"
    SimulationFlowStage.OFFICIAL_COMPONENT_SELECTION -> "选择官方两件套"
    SimulationFlowStage.INTEGRITY_CHECK -> "校验完整性证据"
    SimulationFlowStage.CURRENT_COMPONENT_ASSESSMENT -> "判断当前组件"
    SimulationFlowStage.INSTALLATION_ORDER -> "计算安装顺序"
    SimulationFlowStage.NEXT_ACTION -> "输出下一步动作"
}

@Composable
private fun ComponentFingerprintCard(comparison: OfficialComponentComparison) {
    val fingerprint = comparison.fingerprint
    val status = when (comparison.status) {
        OfficialComponentMatchStatus.VERSION_MATCH -> "与已审计组件版本一致"
        OfficialComponentMatchStatus.VERSION_MISMATCH -> "版本不一致"
        OfficialComponentMatchStatus.NOT_INSTALLED -> "未安装"
        OfficialComponentMatchStatus.UNREADABLE -> "无法读取"
        OfficialComponentMatchStatus.UNKNOWN -> "无法安全判断"
    }
    val signatureStatus = when (comparison.signatureStatus) {
        InstalledArtifactSignatureStatus.ACTUAL_ARTIFACT_MATCH -> "APK 原文件完全匹配"
        InstalledArtifactSignatureStatus.COMPATIBILITY_SIGNATURE_REPORTED ->
            "系统报告兼容签名"
        InstalledArtifactSignatureStatus.SIGNER_MISMATCH -> "报告签名不一致"
        InstalledArtifactSignatureStatus.UNKNOWN -> "签名真实性未知"
    }
    ReportSection(fingerprint.packageName) {
        ReportRow("版本匹配", status)
        ReportRow("签名证据", signatureStatus)
        ReportRow("已安装", if (fingerprint.installed) "是" else "否")
        fingerprint.enabled?.let { ReportRow("已启用", if (it) "是" else "否") }
        fingerprint.versionName?.let { ReportRow("versionName", it) }
        fingerprint.versionCode?.let { ReportRow("versionCode", it.toString()) }
        if (fingerprint.reportedSigningCertificateSha256.isNotEmpty()) {
            ReportRow(
                "系统报告签名证书 SHA-256",
                fingerprint.reportedSigningCertificateSha256.joinToString(),
            )
        }
        fingerprint.installSource?.let { ReportRow("安装来源", it) }
        Text(
            text = "系统报告的兼容签名不证明 APK 原文件一致；只有主机端字节哈希完全匹配才是最高真实性证据，且仍不代表设备兼容性已验证。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun UserValidationSection(
    validation: UserFunctionalValidation,
    onChange: (UserFunctionalValidation) -> Unit,
    validationRecordAvailable: Boolean,
    onExport: () -> Unit,
) {
    ReportSection("真实使用验证") {
        ValidationAnswerRow(
            label = "Google账号可以登录",
            answer = validation.googleAccountLogin,
            onAnswer = { onChange(validation.copy(googleAccountLogin = it)) },
        )
        ValidationAnswerRow(
            label = "ChatGPT可以登录使用",
            answer = validation.chatGptLoginAndUse,
            onAnswer = { onChange(validation.copy(chatGptLoginAndUse = it)) },
        )
        ValidationAnswerRow(
            label = "Chrome可以登录Google",
            answer = validation.chromeGoogleLogin,
            onAnswer = { onChange(validation.copy(chromeGoogleLogin = it)) },
        )
        Text(
            text = if (validationRecordAvailable) {
                "已满足本机验证记录条件；不会修改全局 catalog。"
            } else {
                "只有两件套官方匹配、设备信息完整且三项均确认成功时，才生成本机验证记录。"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onExport,
        ) {
            Text("导出兼容验证报告")
        }
        Text(
            text = "报告通过系统保存，不会由本应用上传。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ValidationAnswerRow(
    label: String,
    answer: UserValidationAnswer,
    onAnswer: (UserValidationAnswer) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        FilterChip(
            selected = answer == UserValidationAnswer.YES,
            onClick = { onAnswer(UserValidationAnswer.YES) },
            label = { Text("是") },
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = answer == UserValidationAnswer.NO,
            onClick = { onAnswer(UserValidationAnswer.NO) },
            label = { Text("否") },
        )
    }
}

@Composable
private fun ComponentCard(component: SystemComponent) {
    val presence = when (component.presence) {
        ComponentPresence.PRESENT -> if (component.enabled == false) "已安装，未启用" else "已安装"
        ComponentPresence.NOT_INSTALLED -> "未安装"
        ComponentPresence.CHECK_FAILED -> "检测失败"
    }
    ReportSection(component.displayName) {
        ReportRow("状态", presence)
        ReportRow("包名", component.packageName)
        component.versionName?.let { ReportRow("版本", it) }
    }
}

@Composable
private fun ReportSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun ReportRow(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(text = value.ifBlank { "未知" }, style = MaterialTheme.typography.bodyMedium)
    }
}
