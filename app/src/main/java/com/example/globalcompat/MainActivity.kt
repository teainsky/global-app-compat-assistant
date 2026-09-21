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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.globalcompat.catalog.InstalledArtifactSignatureStatus
import com.example.globalcompat.data.ComponentPresence
import com.example.globalcompat.data.CompatibilityPlan
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.SystemComponent
import com.example.globalcompat.simulation.CurrentComponentState
import com.example.globalcompat.simulation.SimulatedInstallAction
import com.example.globalcompat.simulation.SimulatedInstallationPlan
import com.example.globalcompat.simulation.SimulationFlowStage
import com.example.globalcompat.simulation.SimulationNextAction
import com.example.globalcompat.ui.theme.GlobalCompatTheme
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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reportFactory = remember { DeviceBaselineReportFactory() }
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
                onExport = {
                    val result = checkNotNull(scanResult)
                    val baseline = reportFactory.create(
                        environment = result.environment,
                        comparisons = result.componentComparisons,
                        functionalValidation = functionalValidation,
                        capturedAtEpochMillis = result.environment.scannedAtEpochMillis,
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
            CompatibilityPlanCard(report.compatibilityPlan)
        }
        item {
            SimulatedInstallationPlanCard(scanResult.simulatedInstallationPlan)
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
private fun CompatibilityPlanCard(plan: CompatibilityPlan) {
    var showTechnicalEvidence by remember(plan) { mutableStateOf(false) }
    val userMessage = when (plan.planId) {
        CompatibilityPlanId.NO_ACTION_REQUIRED -> "Google 环境已完整，无需处理"
        CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN -> "当前设备需要配置兼容环境"
        CompatibilityPlanId.GMS_REPAIR_REQUIRED -> "Google 环境不完整，需要修复"
        CompatibilityPlanId.UNSUPPORTED_OR_UNKNOWN -> "当前系统暂未支持"
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
            text = "仅展示未来流程，不会下载、安装、卸载或修改系统。",
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
