package com.example.globalcompat.installation

import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.data.PlatformFamily
import com.example.globalcompat.data.RomFamily
import com.example.globalcompat.data.RuntimeEnvironment
import com.example.globalcompat.preparation.EnvironmentPreparationResult

enum class InstallationExecutionState {
    BLOCKED,
    WAITING_FOR_INSTALL_PERMISSION,
    READY,
    INSTALLING_COMPONENT_1,
    WAITING_FOR_USER_CONFIRMATION,
    VERIFYING_COMPONENT_1,
    INSTALLING_COMPONENT_2,
    VERIFYING_COMPONENT_2,
    FINAL_ENVIRONMENT_CHECK,
    COMPLETED,
    CANCELLED,
    FAILED,
}

enum class InstallationExecutionFailure {
    EXECUTION_GATE_BLOCKED,
    PREPARATION_NOT_INSTALLABLE,
    HARMONYOS_5_PLUS_NOT_SUPPORTED,
    HARMONY_VERSION_UNKNOWN,
    THIRD_PARTY_COMPAT_RUNTIME_NOT_ALLOWED,
    SESSION_PLAN_INVALID,
    CATALOG_SNAPSHOT_MISMATCH,
    AUTHORIZATION_REJECTED,
    PREPARED_ARTIFACT_MISSING,
    PREPARED_ARTIFACT_REVALIDATION_FAILED,
    INSTALL_PERMISSION_DENIED,
    PACKAGE_INSTALLER_FAILURE,
    POST_INSTALL_VERIFICATION_FAILED,
    FINAL_ENVIRONMENT_CHECK_FAILED,
    INTERRUPTED_SESSION_UNRESOLVED,
}

data class InstallationDeviceContext(
    val deviceCategory: DeviceCategory,
    val manufacturer: String,
    val model: String,
    val platformFamily: PlatformFamily,
    val runtimeEnvironment: RuntimeEnvironment,
    val osVersion: String,
    val androidApiLevel: Int,
    val romFamily: RomFamily,
    val romVersion: String?,
)

data class InstallationExecutionRequest(
    val deviceContext: InstallationDeviceContext,
    val sessionPlan: InstallationSessionPlan,
    val preparationResult: EnvironmentPreparationResult,
    val authorization: InstallAuthorization?,
)

data class ExecutableInstallationArtifact(
    val componentId: String,
    val packageName: String,
    val artifactFilename: String,
    val filePath: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val expectedVersionCode: String,
    val expectedVersionName: String,
    val expectedSigningCertificateSha256: String,
)

data class InstallationExecutionSnapshot(
    val schemaVersion: Int,
    val logicalSessionId: String,
    val state: InstallationExecutionState,
    val deviceContext: InstallationDeviceContext,
    val artifacts: List<ExecutableInstallationArtifact>,
    val nextArtifactIndex: Int,
    val activeArtifactIndex: Int?,
    val packageInstallerSessionId: Int?,
    val failure: InstallationExecutionFailure?,
    val userMessage: String,
    val updatedAtEpochMillis: Long,
    val catalogVersion: Long? = null,
    val catalogDigest: String? = null,
    val authorization: InstallAuthorization? = null,
)

data class PreparedArtifactValidation(
    val matches: Boolean,
    val detail: String,
)

data class InstalledComponentVerification(
    val installed: Boolean,
    val enabled: Boolean,
    val packageName: String?,
    val versionCode: String?,
    val versionName: String?,
    val reportedSigningCertificateSha256: List<String>,
    val reportedSignerAccepted: Boolean,
)

enum class PackageInstallerEvent {
    PENDING_USER_ACTION,
    SUCCESS,
    USER_CANCELLED,
    FAILURE,
}

interface InstallationSessionStore {
    fun load(): InstallationExecutionSnapshot?
    fun save(snapshot: InstallationExecutionSnapshot)
    fun clear()
}

fun interface PreparedArtifactRevalidator {
    fun validate(artifact: ExecutableInstallationArtifact): PreparedArtifactValidation
}

fun interface InstalledComponentPostVerifier {
    fun verify(
        artifact: ExecutableInstallationArtifact,
        deviceContext: InstallationDeviceContext,
    ): InstalledComponentVerification
}

fun interface FinalEnvironmentVerifier {
    fun verify(deviceContext: InstallationDeviceContext): Boolean
}

fun interface InstallationDeviceContextProvider {
    fun current(): InstallationDeviceContext
}

interface PackageInstallerGateway {
    fun hasInstallPermission(): Boolean

    fun commit(
        artifact: ExecutableInstallationArtifact,
        logicalSessionId: String,
    ): Int

    fun isSessionActive(packageInstallerSessionId: Int): Boolean
}
