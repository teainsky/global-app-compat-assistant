package com.example.globalcompat.preparation

import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class EnvironmentPreparationStatus {
    IDLE,
    PREPARING,
    DOWNLOAD_VERIFIED_READY,
    FAIL_CLOSED,
    CANCELLED,
}

enum class EnvironmentPreparationStage {
    WAITING,
    DOWNLOADING,
    VERIFYING,
    COMPLETE,
    FAILED,
    CANCELLED,
}

enum class EnvironmentPreparationFailure {
    CATALOG_NOT_TRUSTED,
    DEVICE_BRANCH_NOT_ALLOWED,
    OFFICIAL_SOURCE_UNAVAILABLE,
    CATALOG_METADATA_INVALID,
    NETWORK_FAILED,
    DOWNLOAD_INCOMPLETE,
    SHA256_MISMATCH,
    PACKAGE_NAME_MISMATCH,
    VERSION_MISMATCH,
    SIGNER_MISMATCH,
    USER_CANCELLED,
}

data class EnvironmentPreparationRequest(
    val deviceCategory: DeviceCategory,
    val planId: CompatibilityPlanId,
    val deviceModel: String,
    val systemVersion: String?,
    val androidApiLevel: Int,
)

data class EnvironmentPreparationProgress(
    val status: EnvironmentPreparationStatus,
    val stage: EnvironmentPreparationStage,
    val componentIndex: Int,
    val componentCount: Int,
    val currentComponent: String?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val userMessage: String,
    val technicalDetail: String? = null,
)

data class PreparedEnvironmentComponent(
    val componentId: String,
    val packageName: String,
    val artifactFilename: String,
    val file: File,
    val sizeBytes: Long,
)

data class EnvironmentPreparationResult(
    val status: EnvironmentPreparationStatus,
    val preparedComponents: List<PreparedEnvironmentComponent>,
    val failures: List<EnvironmentPreparationFailure>,
    val installationAllowed: Boolean,
    val technicalDetails: List<String>,
)

class PreparationCancellation {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()
}

data class ArtifactDownloadReceipt(
    val downloadedBytes: Long,
    val expectedBytes: Long?,
)

data class DownloadedApkMetadata(
    val packageName: String,
    val versionCode: String,
    val versionName: String,
    val signingCertificateSha256: List<String>,
    val signatureVerified: Boolean,
)

fun interface DownloadedApkInspector {
    fun inspect(apkFile: File): DownloadedApkMetadata
}

fun interface OfficialArtifactDownloadTransport {
    fun download(
        sourceUrl: String,
        destination: File,
        cancellation: PreparationCancellation,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): ArtifactDownloadReceipt
}
