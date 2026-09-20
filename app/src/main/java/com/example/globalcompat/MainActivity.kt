package com.example.globalcompat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.globalcompat.data.ComponentPresence
import com.example.globalcompat.data.CompatibilityPlan
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceEnvironmentScanner
import com.example.globalcompat.data.EnvironmentReport
import com.example.globalcompat.data.SystemComponent
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
                    scanner = remember { DeviceEnvironmentScanner(applicationContext) },
                )
            }
        }
    }
}
@Composable
private fun ScannerScreen(scanner: DeviceEnvironmentScanner) {
    var report by remember { mutableStateOf<EnvironmentReport?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold { innerPadding ->
        when {
            isScanning -> LoadingState(Modifier.padding(innerPadding))
            report == null -> StartState(
                modifier = Modifier.padding(innerPadding),
                onStart = {
                    isScanning = true
                    scope.launch {
                        report = withContext(Dispatchers.IO) { scanner.scan() }
                        isScanning = false
                    }
                },
            )
            else -> EnvironmentReportView(
                report = checkNotNull(report),
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
    report: EnvironmentReport,
    modifier: Modifier = Modifier,
) {
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
