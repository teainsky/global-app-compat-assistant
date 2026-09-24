package com.example.globalcompat.installation

import com.example.globalcompat.catalog.TrustedCatalogSnapshotProvider
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.data.PlatformFamily
import com.example.globalcompat.data.RuntimeEnvironment
import com.example.globalcompat.preparation.EnvironmentPreparationStatus
import java.io.File
import java.util.UUID

internal class InstallationExecutor(
    private val sessionStore: InstallationSessionStore,
    private val packageInstallerGateway: PackageInstallerGateway,
    private val preparedArtifactRevalidator: PreparedArtifactRevalidator,
    private val installedComponentVerifier: InstalledComponentPostVerifier,
    private val finalEnvironmentVerifier: FinalEnvironmentVerifier,
    private val deviceContextProvider: InstallationDeviceContextProvider,
    private val catalogProvider: TrustedCatalogSnapshotProvider,
    private val authorizationPolicy: AuthorizationRevalidationPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun begin(request: InstallationExecutionRequest): InstallationExecutionSnapshot {
        sessionStore.load()?.takeUnless { it.state.isTerminal() }?.let { return restore() ?: it }
        val currentDevice = runCatching { deviceContextProvider.current() }.getOrNull()
            ?: return blocked(
                request.deviceContext,
                InstallationExecutionFailure.AUTHORIZATION_REJECTED,
                "无法重新读取当前设备画像，安装授权已拒绝",
            )
        if (currentDevice.deviceCategory == DeviceCategory.HARMONY_VERSION_UNKNOWN ||
            currentDevice.platformFamily == PlatformFamily.HARMONY_VERSION_UNKNOWN
        ) {
            return blocked(
                currentDevice,
                InstallationExecutionFailure.HARMONY_VERSION_UNKNOWN,
                "当前系统版本无法安全识别，暂不执行配置",
            )
        }
        if (currentDevice.runtimeEnvironment == RuntimeEnvironment.THIRD_PARTY_COMPAT_RUNTIME) {
            return blocked(
                currentDevice,
                InstallationExecutionFailure.THIRD_PARTY_COMPAT_RUNTIME_NOT_ALLOWED,
                "第三方兼容运行环境不能进入旧鸿蒙安装流程",
            )
        }
        if (currentDevice.deviceCategory == DeviceCategory.HARMONYOS_5_PLUS ||
            currentDevice.platformFamily == PlatformFamily.HARMONY_NATIVE
        ) {
            return blocked(
                currentDevice,
                InstallationExecutionFailure.HARMONYOS_5_PLUS_NOT_SUPPORTED,
                "HarmonyOS 5+ 不进入旧鸿蒙安装流程",
            )
        }
        if (!request.sessionPlan.executionAllowed ||
            request.sessionPlan.status != InstallationSessionStatus.READY_FOR_USER_CONFIRMATION ||
            request.sessionPlan.blockReasons.isNotEmpty()
        ) {
            return blocked(
                request.deviceContext,
                InstallationExecutionFailure.EXECUTION_GATE_BLOCKED,
                "当前方案尚未完成设备验证，暂不可安装",
            )
        }
        if (request.preparationResult.status !=
            EnvironmentPreparationStatus.DOWNLOAD_VERIFIED_READY ||
            !request.preparationResult.installationAllowed
        ) {
            return blocked(
                request.deviceContext,
                InstallationExecutionFailure.PREPARATION_NOT_INSTALLABLE,
                "准备文件尚未取得安装资格",
            )
        }
        val catalogVersion = request.sessionPlan.catalogVersion
        val catalogDigest = request.sessionPlan.catalogDigest
        if (catalogVersion == null || catalogDigest.isNullOrBlank() ||
            request.preparationResult.catalogVersion != catalogVersion ||
            request.preparationResult.catalogDigest != catalogDigest ||
            request.sessionPlan.steps.any { step ->
                step.downloadRequest?.let { download ->
                    download.catalogVersion != catalogVersion ||
                        download.catalogDigest != catalogDigest
                } == true
            }
        ) {
            return blocked(
                request.deviceContext,
                InstallationExecutionFailure.CATALOG_SNAPSHOT_MISMATCH,
                "安装计划与准备文件使用的规则版本不一致",
                catalogVersion,
                catalogDigest,
            )
        }

        val artifacts = buildArtifacts(request)
            ?: return blocked(
                request.deviceContext,
                InstallationExecutionFailure.SESSION_PLAN_INVALID,
                "安装计划与准备文件不一致",
            )
        if (artifacts.isEmpty() || artifacts.size > MAX_COMPONENTS) {
            return blocked(
                request.deviceContext,
                InstallationExecutionFailure.SESSION_PLAN_INVALID,
                "安装计划中的组件数量无效",
            )
        }
        val authorization = request.authorization
        if (authorization == null ||
            request.sessionPlan.authorization?.authorizationId != authorization.authorizationId ||
            request.sessionPlan.authorization?.authorizationProof != authorization.authorizationProof
        ) {
            return blocked(
                currentDevice,
                InstallationExecutionFailure.AUTHORIZATION_REJECTED,
                "缺少可信安装授权，安装已拒绝",
                catalogVersion,
                catalogDigest,
            )
        }
        val authorizationResult = authorizationPolicy.revalidate(
            authorization = authorization,
            currentDevice = currentDevice,
            activeSnapshot = catalogProvider.currentSnapshot(),
            artifacts = artifacts,
            now = clock(),
        )
        if (!authorizationResult.isAuthorized) {
            return blocked(
                currentDevice,
                InstallationExecutionFailure.AUTHORIZATION_REJECTED,
                "安装授权复核失败：${authorizationResult.reason}",
                catalogVersion,
                catalogDigest,
            )
        }
        val invalidArtifact = artifacts.firstOrNull { artifact ->
            !File(artifact.filePath).isFile ||
                File(artifact.filePath).length() != artifact.expectedSizeBytes ||
                !preparedArtifactRevalidator.validate(artifact).matches
        }
        if (invalidArtifact != null) {
            return blocked(
                request.deviceContext,
                InstallationExecutionFailure.PREPARED_ARTIFACT_REVALIDATION_FAILED,
                "准备文件复检失败，安装已停止",
            )
        }

        val state = if (packageInstallerGateway.hasInstallPermission()) {
            InstallationExecutionState.READY
        } else {
            InstallationExecutionState.WAITING_FOR_INSTALL_PERMISSION
        }
        return save(
            InstallationExecutionSnapshot(
                schemaVersion = SCHEMA_VERSION,
                logicalSessionId = sessionIdFactory(),
                state = state,
                deviceContext = currentDevice,
                artifacts = artifacts,
                nextArtifactIndex = 0,
                activeArtifactIndex = null,
                packageInstallerSessionId = null,
                failure = null,
                userMessage = if (state == InstallationExecutionState.READY) {
                    "准备完成"
                } else {
                    "请先允许此应用请求系统安装确认"
                },
                updatedAtEpochMillis = clock(),
                catalogVersion = catalogVersion,
                catalogDigest = catalogDigest,
                authorization = authorization,
            ),
        )
    }

    fun onInstallPermissionResult(): InstallationExecutionSnapshot? {
        val snapshot = sessionStore.load() ?: return null
        if (snapshot.state != InstallationExecutionState.WAITING_FOR_INSTALL_PERMISSION) {
            return snapshot
        }
        rejectIfAuthorizationInvalid(snapshot)?.let { return it }
        return if (packageInstallerGateway.hasInstallPermission()) {
            save(
                snapshot.copy(
                    state = InstallationExecutionState.READY,
                    failure = null,
                    userMessage = "准备完成",
                    updatedAtEpochMillis = clock(),
                ),
            )
        } else {
            save(
                snapshot.copy(
                    failure = InstallationExecutionFailure.INSTALL_PERMISSION_DENIED,
                    userMessage = "尚未允许安装来源，安装未开始",
                    updatedAtEpochMillis = clock(),
                ),
            )
        }
    }

    fun continueExecution(): InstallationExecutionSnapshot? {
        val snapshot = sessionStore.load() ?: return null
        if (snapshot.state != InstallationExecutionState.READY) return snapshot
        rejectIfAuthorizationInvalid(snapshot)?.let { return it }
        if (!packageInstallerGateway.hasInstallPermission()) {
            return save(
                snapshot.copy(
                    state = InstallationExecutionState.WAITING_FOR_INSTALL_PERMISSION,
                    userMessage = "请先允许此应用请求系统安装确认",
                    updatedAtEpochMillis = clock(),
                ),
            )
        }
        val artifact = snapshot.artifacts.getOrNull(snapshot.nextArtifactIndex)
            ?: return fail(
                snapshot,
                InstallationExecutionFailure.SESSION_PLAN_INVALID,
                "无法确定下一个安装组件",
            )
        val validation = preparedArtifactRevalidator.validate(artifact)
        if (!validation.matches || !File(artifact.filePath).isFile ||
            File(artifact.filePath).length() != artifact.expectedSizeBytes
        ) {
            return fail(
                snapshot,
                InstallationExecutionFailure.PREPARED_ARTIFACT_REVALIDATION_FAILED,
                "准备文件已变化，安装已停止",
            )
        }

        val installing = save(
            snapshot.copy(
                state = snapshot.installingState(),
                activeArtifactIndex = snapshot.nextArtifactIndex,
                packageInstallerSessionId = null,
                failure = null,
                userMessage = "安装必要组件 ${snapshot.nextArtifactIndex + 1}/${snapshot.artifacts.size}",
                updatedAtEpochMillis = clock(),
            ),
        )
        return try {
            val installerSessionId = packageInstallerGateway.commit(
                artifact = artifact,
                logicalSessionId = installing.logicalSessionId,
            )
            save(
                installing.copy(
                    packageInstallerSessionId = installerSessionId,
                    updatedAtEpochMillis = clock(),
                ),
            )
        } catch (_: Exception) {
            fail(
                installing,
                InstallationExecutionFailure.PACKAGE_INSTALLER_FAILURE,
                "系统安装会话创建失败，已安全停止",
            )
        }
    }

    fun onPackageInstallerEvent(
        logicalSessionId: String,
        packageInstallerSessionId: Int,
        event: PackageInstallerEvent,
    ): InstallationExecutionSnapshot? {
        val snapshot = sessionStore.load() ?: return null
        if (snapshot.logicalSessionId != logicalSessionId ||
            snapshot.packageInstallerSessionId != packageInstallerSessionId
        ) {
            return snapshot
        }
        return when (event) {
            PackageInstallerEvent.PENDING_USER_ACTION -> save(
                snapshot.copy(
                    state = InstallationExecutionState.WAITING_FOR_USER_CONFIRMATION,
                    userMessage = "请在系统界面确认安装",
                    updatedAtEpochMillis = clock(),
                ),
            )
            PackageInstallerEvent.USER_CANCELLED -> save(
                snapshot.copy(
                    state = InstallationExecutionState.CANCELLED,
                    packageInstallerSessionId = null,
                    failure = null,
                    userMessage = "用户已取消，配置安全停止",
                    updatedAtEpochMillis = clock(),
                ),
            )
            PackageInstallerEvent.FAILURE -> fail(
                snapshot,
                InstallationExecutionFailure.PACKAGE_INSTALLER_FAILURE,
                "组件安装失败，未继续后续组件",
            )
            PackageInstallerEvent.SUCCESS -> verifyInstalledComponent(snapshot)
        }
    }

    fun cancelBeforeInstall(): InstallationExecutionSnapshot? {
        val snapshot = sessionStore.load() ?: return null
        if (snapshot.state !in setOf(
                InstallationExecutionState.READY,
                InstallationExecutionState.WAITING_FOR_INSTALL_PERMISSION,
            )
        ) {
            return snapshot
        }
        return save(
            snapshot.copy(
                state = InstallationExecutionState.CANCELLED,
                userMessage = "用户已取消，配置安全停止",
                updatedAtEpochMillis = clock(),
            ),
        )
    }

    fun restore(): InstallationExecutionSnapshot? {
        val snapshot = sessionStore.load() ?: return null
        if (!snapshot.state.isTerminal()) {
            rejectIfAuthorizationInvalid(snapshot)?.let { return it }
        }
        return when (snapshot.state) {
            InstallationExecutionState.WAITING_FOR_INSTALL_PERMISSION ->
                onInstallPermissionResult()
            InstallationExecutionState.INSTALLING_COMPONENT_1,
            InstallationExecutionState.INSTALLING_COMPONENT_2,
            InstallationExecutionState.WAITING_FOR_USER_CONFIRMATION,
            -> {
                val installerSessionId = snapshot.packageInstallerSessionId
                if (installerSessionId != null &&
                    packageInstallerGateway.isSessionActive(installerSessionId)
                ) {
                    snapshot
                } else {
                    fail(
                        snapshot,
                        InstallationExecutionFailure.INTERRUPTED_SESSION_UNRESOLVED,
                        "上次系统安装结果无法确认，未重复安装",
                    )
                }
            }
            InstallationExecutionState.VERIFYING_COMPONENT_1,
            InstallationExecutionState.VERIFYING_COMPONENT_2,
            -> verifyInstalledComponent(snapshot)
            InstallationExecutionState.FINAL_ENVIRONMENT_CHECK -> finalCheck(snapshot)
            else -> snapshot
        }
    }

    private fun verifyInstalledComponent(
        snapshot: InstallationExecutionSnapshot,
    ): InstallationExecutionSnapshot {
        val activeIndex = snapshot.activeArtifactIndex
            ?: return fail(
                snapshot,
                InstallationExecutionFailure.SESSION_PLAN_INVALID,
                "安装会话缺少当前组件",
            )
        val artifact = snapshot.artifacts.getOrNull(activeIndex)
            ?: return fail(
                snapshot,
                InstallationExecutionFailure.SESSION_PLAN_INVALID,
                "安装会话组件索引无效",
            )
        val verifying = save(
            snapshot.copy(
                state = snapshot.verifyingState(activeIndex),
                packageInstallerSessionId = null,
                userMessage = "正在检查已安装组件",
                updatedAtEpochMillis = clock(),
            ),
        )
        val verification = runCatching {
            installedComponentVerifier.verify(artifact, snapshot.deviceContext)
        }.getOrNull()
        if (verification == null || !verification.matches(artifact)) {
            return fail(
                verifying,
                InstallationExecutionFailure.POST_INSTALL_VERIFICATION_FAILED,
                "安装后版本或签名校验异常，未继续后续组件",
            )
        }
        val nextIndex = activeIndex + 1
        return if (nextIndex < snapshot.artifacts.size) {
            save(
                verifying.copy(
                    state = InstallationExecutionState.READY,
                    nextArtifactIndex = nextIndex,
                    activeArtifactIndex = null,
                    userMessage = "组件 ${nextIndex}/${snapshot.artifacts.size} 已通过检查",
                    updatedAtEpochMillis = clock(),
                ),
            )
        } else {
            finalCheck(
                save(
                    verifying.copy(
                        state = InstallationExecutionState.FINAL_ENVIRONMENT_CHECK,
                        nextArtifactIndex = nextIndex,
                        activeArtifactIndex = null,
                        userMessage = "正在检查环境",
                        updatedAtEpochMillis = clock(),
                    ),
                ),
            )
        }
    }

    private fun finalCheck(
        snapshot: InstallationExecutionSnapshot,
    ): InstallationExecutionSnapshot = if (
        runCatching { finalEnvironmentVerifier.verify(snapshot.deviceContext) }.getOrDefault(false)
    ) {
        save(
            snapshot.copy(
                state = InstallationExecutionState.COMPLETED,
                userMessage = "配置完成",
                failure = null,
                updatedAtEpochMillis = clock(),
            ),
        )
    } else {
        fail(
            snapshot,
            InstallationExecutionFailure.FINAL_ENVIRONMENT_CHECK_FAILED,
            "最终环境检查未通过，不会自动卸载或回滚",
        )
    }

    private fun buildArtifacts(
        request: InstallationExecutionRequest,
    ): List<ExecutableInstallationArtifact>? {
        val executableSteps = request.sessionPlan.steps
            .filter { it.state == InstallationStepState.READY_FOR_USER_CONFIRMATION }
        if (executableSteps.any { it.order == null } ||
            executableSteps.mapNotNull { it.order }.distinct().size != executableSteps.size
        ) {
            return null
        }
        val preparedByComponent = request.preparationResult.preparedComponents
            .groupBy { it.componentId }
        return executableSteps.sortedBy { it.order }.map { step ->
            val download = step.downloadRequest ?: return null
            val verification = step.verificationResult ?: return null
            val prepared = preparedByComponent[step.componentId]?.singleOrNull() ?: return null
            if (prepared.packageName != download.packageName ||
                prepared.artifactFilename != download.artifactFilename ||
                verification.componentId != step.componentId ||
                !verification.downloadEvidencePresent ||
                verification.locallyCalculatedSha256.normalizeDigest() !=
                download.expectedSha256.normalizeDigest() ||
                verification.packageName != download.packageName ||
                verification.versionCode != download.artifactVersionCode ||
                verification.versionName != download.artifactVersionName ||
                !verification.apkSignatureVerificationPassed ||
                verification.signingCertificateSha256.map { it.normalizeDigest() }.toSet() !=
                setOf(download.expectedSigningCertificateSha256.normalizeDigest())
            ) {
                return null
            }
            ExecutableInstallationArtifact(
                componentId = step.componentId,
                packageName = download.packageName,
                artifactFilename = download.artifactFilename,
                filePath = prepared.file.absolutePath,
                expectedSizeBytes = prepared.sizeBytes,
                expectedSha256 = download.expectedSha256,
                expectedVersionCode = download.artifactVersionCode,
                expectedVersionName = download.artifactVersionName,
                expectedSigningCertificateSha256 =
                    download.expectedSigningCertificateSha256,
            )
        }
    }

    private fun InstallationExecutionSnapshot.installingState(): InstallationExecutionState =
        if (nextArtifactIndex == 0) {
            InstallationExecutionState.INSTALLING_COMPONENT_1
        } else {
            InstallationExecutionState.INSTALLING_COMPONENT_2
        }

    private fun InstallationExecutionSnapshot.verifyingState(
        index: Int,
    ): InstallationExecutionState = if (index == 0) {
        InstallationExecutionState.VERIFYING_COMPONENT_1
    } else {
        InstallationExecutionState.VERIFYING_COMPONENT_2
    }

    private fun InstalledComponentVerification.matches(
        artifact: ExecutableInstallationArtifact,
    ): Boolean = installed && enabled &&
        packageName == artifact.packageName &&
        versionCode == artifact.expectedVersionCode &&
        versionName == artifact.expectedVersionName &&
        reportedSigningCertificateSha256.isNotEmpty() &&
        reportedSignerAccepted

    private fun rejectIfAuthorizationInvalid(
        snapshot: InstallationExecutionSnapshot,
    ): InstallationExecutionSnapshot? {
        val authorization = snapshot.authorization
            ?: return fail(
                snapshot,
                InstallationExecutionFailure.AUTHORIZATION_REJECTED,
                "恢复的安装会话缺少可信授权",
            )
        val currentDevice = runCatching { deviceContextProvider.current() }.getOrNull()
            ?: return fail(
                snapshot,
                InstallationExecutionFailure.AUTHORIZATION_REJECTED,
                "无法重新读取当前设备画像，安装授权已拒绝",
            )
        val result = authorizationPolicy.revalidate(
            authorization = authorization,
            currentDevice = currentDevice,
            activeSnapshot = catalogProvider.currentSnapshot(),
            artifacts = snapshot.artifacts,
            now = clock(),
        )
        return if (result.isAuthorized) {
            null
        } else {
            fail(
                snapshot,
                InstallationExecutionFailure.AUTHORIZATION_REJECTED,
                "安装授权复核失败：${result.reason}",
            )
        }
    }

    private fun blocked(
        context: InstallationDeviceContext,
        failure: InstallationExecutionFailure,
        message: String,
        catalogVersion: Long? = null,
        catalogDigest: String? = null,
    ): InstallationExecutionSnapshot = save(
        InstallationExecutionSnapshot(
            schemaVersion = SCHEMA_VERSION,
            logicalSessionId = sessionIdFactory(),
            state = InstallationExecutionState.BLOCKED,
            deviceContext = context,
            artifacts = emptyList(),
            nextArtifactIndex = 0,
            activeArtifactIndex = null,
            packageInstallerSessionId = null,
            failure = failure,
            userMessage = message,
            updatedAtEpochMillis = clock(),
            catalogVersion = catalogVersion,
            catalogDigest = catalogDigest,
            authorization = null,
        ),
    )

    private fun fail(
        snapshot: InstallationExecutionSnapshot,
        failure: InstallationExecutionFailure,
        message: String,
    ): InstallationExecutionSnapshot = save(
        snapshot.copy(
            state = InstallationExecutionState.FAILED,
            packageInstallerSessionId = null,
            failure = failure,
            userMessage = message,
            updatedAtEpochMillis = clock(),
        ),
    )

    private fun save(
        snapshot: InstallationExecutionSnapshot,
    ): InstallationExecutionSnapshot = snapshot.also(sessionStore::save)

    private fun InstallationExecutionState.isTerminal(): Boolean = this in setOf(
        InstallationExecutionState.BLOCKED,
        InstallationExecutionState.COMPLETED,
        InstallationExecutionState.CANCELLED,
        InstallationExecutionState.FAILED,
    )

    private fun String?.normalizeDigest(): String =
        this?.replace(":", "")?.lowercase().orEmpty()

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_COMPONENTS = 2
    }
}
