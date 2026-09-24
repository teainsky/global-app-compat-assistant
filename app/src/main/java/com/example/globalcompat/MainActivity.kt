package com.example.globalcompat

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.example.globalcompat.baseline.DeviceBaselineScanResult
import com.example.globalcompat.baseline.DeviceBaselineScanner
import com.example.globalcompat.baseline.OfficialComponentComparison
import com.example.globalcompat.baseline.OfficialComponentMatchStatus
import com.example.globalcompat.baseline.UserFunctionalValidation
import com.example.globalcompat.baseline.UserValidationAnswer
import com.example.globalcompat.artifact.OnDeviceArtifactAuditAvailability
import com.example.globalcompat.artifact.OnDeviceArtifactAuditReport
import com.example.globalcompat.artifact.OnDeviceArtifactReadStatus
import com.example.globalcompat.catalog.InstalledArtifactSignatureStatus
import com.example.globalcompat.data.ComponentPresence
import com.example.globalcompat.data.CompatibilityPlan
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.CompatibilityDecision
import com.example.globalcompat.data.CompatibilityDecisionStatus
import com.example.globalcompat.data.DeviceProfile
import com.example.globalcompat.data.FreeMvpCapability
import com.example.globalcompat.data.FreeMvpConfigurationStatus
import com.example.globalcompat.data.PlatformFamily
import com.example.globalcompat.data.RuntimeEnvironment
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
import com.example.globalcompat.update.AndroidReleaseUpdater
import com.example.globalcompat.update.AvailableRelease
import com.example.globalcompat.update.ReleaseUpdateNotification
import com.example.globalcompat.update.ReleaseUpdateStatus
import com.example.globalcompat.update.ReleaseUpdateUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var openUpdateDetails by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openUpdateDetails = intent.getBooleanExtra(ReleaseUpdateNotification.EXTRA_OPEN_UPDATE, false)
        enableEdgeToEdge()
        setContent {
            GlobalCompatTheme {
                ScannerScreen(
                    scanner = remember { DeviceBaselineScanner(applicationContext) },
                    openUpdateDetails = openUpdateDetails,
                    onUpdateDetailsOpened = { openUpdateDetails = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(ReleaseUpdateNotification.EXTRA_OPEN_UPDATE, false)) {
            openUpdateDetails = true
        }
    }
}
@Composable
private fun ScannerScreen(
    scanner: DeviceBaselineScanner,
    openUpdateDetails: Boolean,
    onUpdateDetailsOpened: () -> Unit,
) {
    var scanResult by remember { mutableStateOf<DeviceBaselineScanResult?>(null) }
    var functionalValidation by remember { mutableStateOf(UserFunctionalValidation()) }
    var showFreeMvpInfo by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var preparationProgress by remember { mutableStateOf<EnvironmentPreparationProgress?>(null) }
    var preparationResult by remember { mutableStateOf<EnvironmentPreparationResult?>(null) }
    var activePreparation by remember { mutableStateOf<PreparationCancellation?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val releaseUpdater = remember(context.applicationContext) {
        AndroidReleaseUpdater(context.applicationContext)
    }
    var updateState by remember {
        mutableStateOf(releaseUpdater.initialState(BuildConfig.VERSION_NAME))
    }
    var verifiedUpdateApk by remember { mutableStateOf<File?>(null) }
    var pendingNotificationRelease by remember { mutableStateOf<AvailableRelease?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingNotificationRelease?.let { ReleaseUpdateNotification.show(context, it) }
        }
        pendingNotificationRelease = null
    }
    val preparationService = remember(context.applicationContext) {
        AndroidEnvironmentPreparationService(context.applicationContext)
    }
    DisposableEffect(Unit) {
        onDispose { activePreparation?.cancel() }
    }
    LaunchedEffect(Unit) {
        updateState = updateState.copy(status = ReleaseUpdateStatus.CHECKING)
        val checked = withContext(Dispatchers.IO) {
            releaseUpdater.checkIfDue(BuildConfig.VERSION_NAME)
        }
        updateState = checked
        checked.release?.takeIf { checked.status == ReleaseUpdateStatus.UPDATE_AVAILABLE }
            ?.let { release ->
                if (ReleaseUpdateNotification.hasPermission(context)) {
                    ReleaseUpdateNotification.show(context, release)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pendingNotificationRelease = release
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
    }
    LaunchedEffect(openUpdateDetails) {
        if (openUpdateDetails) {
            showFreeMvpInfo = true
            onUpdateDetailsOpened()
        }
    }
    Scaffold { innerPadding ->
        when {
            showFreeMvpInfo -> FreeMvpInfoScreen(
                updateState = updateState,
                modifier = Modifier.padding(innerPadding),
                onBack = { showFreeMvpInfo = false },
                onDownloadUpdate = { release ->
                    updateState = ReleaseUpdateUiState(
                        status = ReleaseUpdateStatus.DOWNLOADING,
                        release = release,
                        totalBytes = release.asset.sizeBytes,
                    )
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                releaseUpdater.downloadAndVerify(release) { downloaded, total ->
                                    scope.launch {
                                        updateState = updateState.copy(
                                            status = if (downloaded == total) {
                                                ReleaseUpdateStatus.VERIFYING
                                            } else {
                                                ReleaseUpdateStatus.DOWNLOADING
                                            },
                                            downloadedBytes = downloaded,
                                            totalBytes = total,
                                        )
                                    }
                                }
                            }
                        }
                        outcome.fold(
                            onSuccess = { (apk, _) ->
                                verifiedUpdateApk = apk
                                updateState = updateState.copy(
                                    status = ReleaseUpdateStatus.READY_TO_INSTALL,
                                    downloadedBytes = release.asset.sizeBytes,
                                    totalBytes = release.asset.sizeBytes,
                                    message = "下载和安全校验已完成。",
                                )
                                if (releaseUpdater.canRequestPackageInstalls()) {
                                    context.startActivity(releaseUpdater.installIntent(apk))
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    updateState = updateState.copy(
                                        status = ReleaseUpdateStatus.WAITING_FOR_INSTALL_PERMISSION,
                                        message = "请按系统提示允许此来源安装，再继续更新。",
                                    )
                                    context.startActivity(releaseUpdater.installPermissionIntent())
                                }
                            },
                            onFailure = {
                                verifiedUpdateApk = null
                                updateState = updateState.copy(
                                    status = ReleaseUpdateStatus.FAILED,
                                    message = "更新文件下载或安全校验失败，已删除临时文件。",
                                )
                            },
                        )
                    }
                },
                onContinueInstall = {
                    val apk = verifiedUpdateApk
                    if (apk == null) {
                        updateState = updateState.copy(
                            status = ReleaseUpdateStatus.FAILED,
                            message = "已验证更新文件不可用，请重新下载。",
                        )
                    } else if (releaseUpdater.canRequestPackageInstalls()) {
                        updateState = updateState.copy(status = ReleaseUpdateStatus.READY_TO_INSTALL)
                        context.startActivity(releaseUpdater.installIntent(apk))
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startActivity(releaseUpdater.installPermissionIntent())
                    }
                },
            )
            isScanning -> LoadingState(Modifier.padding(innerPadding))
            scanResult == null -> StartState(
                updateAvailable = updateState.status == ReleaseUpdateStatus.UPDATE_AVAILABLE,
                modifier = Modifier.padding(innerPadding),
                onAbout = { showFreeMvpInfo = true },
                onStart = {
                    activePreparation?.cancel()
                    activePreparation = null
                    preparationProgress = null
                    preparationResult = null
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
                onAbout = { showFreeMvpInfo = true },
                updateAvailable = updateState.status == ReleaseUpdateStatus.UPDATE_AVAILABLE,
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
                                    platformFamily = result.environment.deviceProfile.platformFamily,
                                    runtimeEnvironment =
                                        result.environment.deviceProfile.runtimeEnvironment,
                                    planId = result.environment.compatibilityPlan.planId,
                                    deviceModel = result.environment.device.model,
                                    systemVersion = result.environment.rom.version,
                                    androidApiLevel = result.environment.android.apiLevel,
                                    validationLevel = result.environment.deviceProfile.validationLevel,
                                    compatibilityDecisionStatus =
                                        result.environment.compatibilityDecision.decisionStatus,
                                    catalogVersion = result.environment.compatibilityDecision
                                        .catalogVersion,
                                    catalogDigest = result.environment.compatibilityDecision
                                        .catalogDigest,
                                ),
                                catalogSnapshot = result.catalogSnapshot,
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
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun StartState(
    modifier: Modifier = Modifier,
    updateAvailable: Boolean,
    onStart: () -> Unit,
    onAbout: () -> Unit,
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
        TextButton(onClick = onAbout) {
            Text(if (updateAvailable) "关于 / 更新  1" else "关于 / 更新")
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
    onAbout: () -> Unit,
    updateAvailable: Boolean,
    preparationProgress: EnvironmentPreparationProgress?,
    preparationResult: EnvironmentPreparationResult?,
    onPrepareEnvironment: () -> Unit,
    onCancelPreparation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = scanResult.environment
    val verifiedConfigurationAvailable = FreeMvpCapability.VERIFIED_CONFIGURATION in
        report.compatibilityDecision.freeMvpCoverage.capabilities
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
            ReportSection("检测结果") {
                ReportRow("设备", listOf(report.device.brand, report.device.model)
                    .filter { it.isNotBlank() }
                    .joinToString(" "))
                if (report.deviceProfile.runtimeEnvironment ==
                    RuntimeEnvironment.THIRD_PARTY_COMPAT_RUNTIME
                ) {
                    ReportRow("检测到的运行环境", "HarmonyOS 兼容环境")
                    ReportRow("手机原生系统", "未确认")
                } else {
                    ReportRow("系统", report.rom.displayName)
                }
                ReportRow("Google 环境", report.compatibilityDecision.userSummary())
            }
        }
        if (report.deviceProfile.runtimeEnvironment ==
            RuntimeEnvironment.THIRD_PARTY_COMPAT_RUNTIME
        ) {
            item {
                ReportSection("兼容环境提示") {
                    Text(
                        "当前应用运行在兼容环境中，检测结果代表该兼容环境，不代表手机原生系统。",
                    )
                }
            }
        }
        item {
            FreeMvpCoverageCard(report.deviceProfile, report.compatibilityDecision)
        }
        item {
            CompatibilityDecisionCard(report.compatibilityDecision)
        }
        if (verifiedConfigurationAvailable) {
            item {
                if (report.compatibilityDecision.decisionStatus ==
                    CompatibilityDecisionStatus.NO_ACTION_REQUIRED
                ) {
                    ReportSection("已验证设备配置") {
                        Text("当前组件状态正确，无需重新配置。")
                        Text(
                            "仅在检测到缺失且安全门禁通过时，才会提供环境准备入口。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    EnvironmentPreparationCard(
                        plan = report.compatibilityPlan,
                        progress = preparationProgress,
                        result = preparationResult,
                        onPrepare = onPrepareEnvironment,
                        onCancel = onCancelPreparation,
                    )
                }
            }
        }
        item {
            UserValidationSection(
                validation = functionalValidation,
                onChange = onFunctionalValidationChange,
            )
        }
        item { RecoveryGuidanceCard() }
        item { TechnicalDetailsCard(scanResult) }
        item {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onAbout,
            ) {
                Text(if (updateAvailable) "关于 / 更新  1" else "关于 / 更新")
            }
        }
    }
}

private fun CompatibilityDecision.userSummary(): String = when (decisionStatus) {
    CompatibilityDecisionStatus.NO_ACTION_REQUIRED -> "已验证，当前无需处理"
    CompatibilityDecisionStatus.VERIFIED_WORKFLOW_AVAILABLE -> "已验证，可准备配置"
    CompatibilityDecisionStatus.DIAGNOSTIC_ONLY -> "发现问题，当前仅提供诊断"
    CompatibilityDecisionStatus.CURRENT_WORKFLOW_NOT_APPLICABLE -> "现有配置流程不适用"
    CompatibilityDecisionStatus.UNKNOWN -> "证据不足，暂无法安全判断"
    CompatibilityDecisionStatus.BLOCKED_BY_KNOWN_RULE -> "已由安全规则停止"
}

@Composable
private fun FreeMvpInfoScreen(
    updateState: ReleaseUpdateUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onDownloadUpdate: (AvailableRelease) -> Unit,
    onContinueInstall: () -> Unit,
) {
    var confirmUpdate by remember(updateState.release) { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "关于 / 更新",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            ReleaseUpdateCard(
                state = updateState,
                confirmUpdate = confirmUpdate,
                onRequestConfirmation = { confirmUpdate = true },
                onCancelConfirmation = { confirmUpdate = false },
                onDownload = onDownloadUpdate,
                onContinueInstall = onContinueInstall,
            )
        }
        item {
            ReportSection("这是一个免费工具") {
                Text("用于检测设备环境、诊断 Google 运行环境，并在实机验证范围内提供配置入口。")
                Text("不 Root、不解锁 Bootloader、不修改 ROM。")
            }
        }
        item {
            ReportSection("当前支持范围") {
                Text("已验证自动配置设备：")
                Text(
                    "Huawei Pura 70 Pro+ / HBN-AL80 / HarmonyOS 4.2",
                    fontWeight = FontWeight.SemiBold,
                )
                Text("其他主流 Android 设备目前以检测和诊断为主，不保证都可配置。")
                Text("HarmonyOS 5/6 不进入旧鸿蒙配置流程。")
                Text("TikTok 不在首版支持范围。")
            }
        }
        item {
            ReportSection("安全边界") {
                Text("未通过精确设备验证时，不会开放自动配置。")
                Text("不会静默安装、自动卸载，也不会上传检测报告。")
                Text("系统版本或证据不足时会安全停止，并保留诊断结果。")
            }
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onBack,
            ) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun ReleaseUpdateCard(
    state: ReleaseUpdateUiState,
    confirmUpdate: Boolean,
    onRequestConfirmation: () -> Unit,
    onCancelConfirmation: () -> Unit,
    onDownload: (AvailableRelease) -> Unit,
    onContinueInstall: () -> Unit,
) {
    val release = state.release
    ReportSection("版本更新") {
        when (state.status) {
            ReleaseUpdateStatus.IDLE,
            ReleaseUpdateStatus.CHECKING,
            -> Text("每天最多自动检查一次 GitHub 正式版本。")

            ReleaseUpdateStatus.UP_TO_DATE -> Text("当前已是最新版本。")
            ReleaseUpdateStatus.UNAVAILABLE -> Text("暂时无法检查更新，不影响设备检测。")
            ReleaseUpdateStatus.UPDATE_AVAILABLE -> if (release != null) {
                Text("发现新版本 ${release.versionName}", fontWeight = FontWeight.SemiBold)
                Text(release.title)
                if (release.notes.isNotBlank()) {
                    Text(release.notes, style = MaterialTheme.typography.bodySmall)
                }
                if (confirmUpdate) {
                    Text("确认后将从官方 GitHub 下载，并校验文件摘要和正式签名。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDownload(release) }) { Text("确认下载并更新") }
                        TextButton(onClick = onCancelConfirmation) { Text("取消") }
                    }
                } else {
                    Button(onClick = onRequestConfirmation) { Text("立即更新") }
                }
            }

            ReleaseUpdateStatus.DOWNLOADING -> {
                Text("正在下载新版本…")
                val total = state.totalBytes
                if (total != null && total > 0L) {
                    LinearProgressIndicator(
                        progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${state.downloadedBytes.userFileSize()} / ${total.userFileSize()}")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            ReleaseUpdateStatus.VERIFYING -> Text("正在进行安全校验…")
            ReleaseUpdateStatus.WAITING_FOR_INSTALL_PERMISSION -> {
                Text(state.message ?: "请允许此来源安装后继续。")
                Button(onClick = onContinueInstall) { Text("继续安装") }
            }

            ReleaseUpdateStatus.READY_TO_INSTALL -> {
                Text("安全校验已通过，等待系统安装界面确认。")
                Button(onClick = onContinueInstall) { Text("打开系统安装界面") }
            }

            ReleaseUpdateStatus.FAILED -> Text(
                state.message ?: "更新失败，未保留未通过校验的文件。",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "更新不是强制操作；系统桌面角标为尽力显示，可能是数字、红点或不显示。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RecoveryGuidanceCard() {
    ReportSection("常见错误 / 恢复说明") {
        Text("检测失败：关闭应用后重新打开，再次开始检测。")
        Text("组件无法读取：确认组件未被系统停用，然后重新检测。")
        Text("准备中断：检查网络后重试；未完成或校验失败的文件不会继续使用。")
        Text("系统版本无法识别：不要强行配置，等待规则更新或提交 Issue。")
    }
}

@Composable
private fun TechnicalDetailsCard(scanResult: DeviceBaselineScanResult) {
    var expanded by remember(scanResult) { mutableStateOf(false) }
    val report = scanResult.environment
    ReportSection("技术详情") {
        Text("供排查和 Issue 反馈使用，普通使用无需展开。")
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起技术详情" else "展开技术详情")
        }
        if (expanded) {
            ReportRow("制造商", report.device.manufacturer)
            ReportRow("品牌", report.device.brand)
            ReportRow("型号", report.device.model)
            ReportRow("设备代号", report.device.device)
            ReportRow("Android API", report.android.apiLevel.toString())
            ReportRow("Android 版本", report.android.release)
            ReportRow("系统构建", report.android.buildDisplay)
            ReportRow("ROM", report.rom.displayName)
            ReportRow("ROM 版本", report.rom.version ?: "未识别")
            ReportRow("平台分类", report.deviceProfile.platformFamily.name)
            ReportRow("运行环境", report.deviceProfile.runtimeEnvironment.name)
            ReportRow("验证等级", report.deviceProfile.validationLevel.name)
            ReportRow("决策", report.compatibilityDecision.decisionStatus.name)
            ReportRow("适用流程", report.compatibilityDecision.applicableWorkflow.name)
            ReportRow("方案", report.compatibilityPlan.planId.name)
            scanResult.catalogSnapshot?.let { snapshot ->
                ReportRow("Catalog 版本", snapshot.catalogVersion.toString())
                ReportRow("Catalog 来源", snapshot.source.name)
                ReportRow("Catalog 摘要", snapshot.catalogDigest)
            }
            report.components.forEach { component ->
                ReportRow(
                    component.displayName,
                    "${component.presence.name} / ${component.versionName ?: "版本未知"}",
                )
            }
            scanResult.componentComparisons.forEach { comparison ->
                ReportRow(
                    comparison.fingerprint.packageName,
                    "${comparison.status.name} / ${comparison.signatureStatus.name}",
                )
            }
            report.compatibilityDecision.evidence.forEach { evidence ->
                Text(
                    "• ${evidence.code}：${evidence.observedValue}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            (report.compatibilityDecision.blockers + report.compatibilityDecision.warnings)
                .forEach { message ->
                    Text(
                        "• ${message.code}：${message.message}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
                text = "当前系统不适用现有 Android 兼容环境工作流。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!branchAllowed && plan.deviceCategory ==
            com.example.globalcompat.data.DeviceCategory.HARMONY_VERSION_UNKNOWN
        ) {
            Text(
                text = "当前系统版本无法安全识别，暂不执行配置。",
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
private fun FreeMvpCoverageCard(
    profile: DeviceProfile,
    decision: CompatibilityDecision,
) {
    var showTechnicalDetails by remember(decision) { mutableStateOf(false) }
    val coverage = decision.freeMvpCoverage
    val summary = when {
        FreeMvpCapability.VERIFIED_CONFIGURATION in coverage.capabilities ->
            "此设备已有精确验证，可使用已验证配置流程"
        profile.platformFamily == PlatformFamily.UNKNOWN -> "可检测，未验证"
        coverage.configurationStatus == FreeMvpConfigurationStatus.WORKFLOW_NOT_APPLICABLE ->
            "可检测、可诊断；现有配置工作流不适用"
        else -> "可检测、可诊断；自动配置尚未通过精确设备验证"
    }

    ReportSection("免费版能力") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            coverage.capabilities.forEach { capability ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = capability.userLabel(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        Text(summary, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails }) {
            Text(if (showTechnicalDetails) "收起技术详情" else "查看技术详情")
        }
        if (showTechnicalDetails) {
            ReportRow("平台", profile.platformFamily.name)
            ReportRow("ROM", profile.romFamily.name)
            ReportRow("市场版本", profile.marketVariant.name)
            ReportRow("Google 组件集合", profile.googleEnvironmentAssessment.componentSetState.name)
            ReportRow("组件可信度", profile.googleEnvironmentAssessment.componentTrust.name)
            ReportRow("功能健康", profile.googleEnvironmentAssessment.functionalHealth.name)
            ReportRow("Play 认证", profile.googleEnvironmentAssessment.playCertification.name)
            ReportRow("安装能力", profile.installationCapability.name)
            ReportRow("验证等级", profile.validationLevel.name)
            ReportRow("配置覆盖", coverage.configurationStatus.name)
            decision.catalogVersion?.let { ReportRow("Catalog 版本", it.toString()) }
            decision.catalogDigest?.let { ReportRow("Catalog 摘要", it) }
        }
    }
}

private fun FreeMvpCapability.userLabel(): String = when (this) {
    FreeMvpCapability.DETECTION -> "可检测"
    FreeMvpCapability.GOOGLE_DIAGNOSTICS -> "可诊断"
    FreeMvpCapability.VERIFIED_CONFIGURATION -> "已验证可配置"
}

@Composable
private fun CompatibilityDecisionCard(decision: CompatibilityDecision) {
    var showTechnicalEvidence by remember(decision) { mutableStateOf(false) }
    val userMessage = when (decision.decisionStatus) {
        CompatibilityDecisionStatus.NO_ACTION_REQUIRED -> "当前已验证组件状态无需处理"
        CompatibilityDecisionStatus.VERIFIED_WORKFLOW_AVAILABLE -> "已有可信方案，可以准备环境"
        CompatibilityDecisionStatus.DIAGNOSTIC_ONLY -> "检测到环境问题，当前仅提供诊断"
        CompatibilityDecisionStatus.CURRENT_WORKFLOW_NOT_APPLICABLE -> if (
            decision.warnings.any { it.code == "HARMONY_VERSION_UNKNOWN" }
        ) {
            "当前系统版本无法安全识别，暂不执行配置"
        } else {
            "当前系统不适用现有 Android 兼容环境工作流"
        }
        CompatibilityDecisionStatus.UNKNOWN -> "证据不足，暂时无法安全判断"
        CompatibilityDecisionStatus.BLOCKED_BY_KNOWN_RULE -> "可信规则已阻止当前流程"
    }

    ReportSection("Google 环境说明") {
        Text(
            text = userMessage,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = { showTechnicalEvidence = !showTechnicalEvidence }) {
            Text(if (showTechnicalEvidence) "收起技术证据" else "查看技术证据")
        }
        if (showTechnicalEvidence) {
            ReportRow("下一步", decision.nextAction.name)
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
        InstallationBlockReason.HARMONY_VERSION_UNKNOWN in plan.blockReasons ->
            "系统版本无法安全识别，暂不安装"
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
) {
    val allPassed = validation.googleAccountLogin == UserValidationAnswer.YES &&
        validation.chatGptLoginAndUse == UserValidationAnswer.YES &&
        validation.chromeGoogleLogin == UserValidationAnswer.YES
    val anyFailed = validation.googleAccountLogin == UserValidationAnswer.NO ||
        validation.chatGptLoginAndUse == UserValidationAnswer.NO ||
        validation.chromeGoogleLogin == UserValidationAnswer.NO
    ReportSection("自检") {
        Text("请按实际使用结果选择；自检不会单独解锁配置能力。")
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
            text = when {
                allPassed -> "三项功能自检均通过。"
                anyFailed -> "存在未通过项目，请查看上方 Google 环境说明和恢复建议。"
                else -> "尚有项目未测试；未测试不代表失败，也不会提升验证等级。"
            },
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
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = answer == UserValidationAnswer.NOT_TESTED,
            onClick = { onAnswer(UserValidationAnswer.NOT_TESTED) },
            label = { Text("未测试") },
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
