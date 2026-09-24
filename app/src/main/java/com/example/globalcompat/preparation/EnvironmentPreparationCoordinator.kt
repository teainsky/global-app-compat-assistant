package com.example.globalcompat.preparation

import com.example.globalcompat.catalog.ArtifactIntegrityStatus
import com.example.globalcompat.catalog.ArtifactSourceRecord
import com.example.globalcompat.catalog.CatalogMatchRequest
import com.example.globalcompat.catalog.CatalogSnapshot
import com.example.globalcompat.catalog.CatalogSystemFamily
import com.example.globalcompat.catalog.CompatibilityValidationStatus
import com.example.globalcompat.catalog.ComponentArtifact
import com.example.globalcompat.catalog.ComponentRelease
import com.example.globalcompat.catalog.ComponentSourceType
import com.example.globalcompat.catalog.ComponentVariant
import com.example.globalcompat.catalog.SourceAvailabilityStatus
import com.example.globalcompat.catalog.TrustedComponentCatalogMatcher
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.CompatibilityDecisionStatus
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.data.GlobalValidationLevel
import com.example.globalcompat.data.PlatformFamily
import com.example.globalcompat.data.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

class EnvironmentPreparationCoordinator(
    private val catalogSnapshot: CatalogSnapshot?,
    private val downloadTransport: OfficialArtifactDownloadTransport,
    private val apkInspector: DownloadedApkInspector,
    private val privateTemporaryDirectory: File,
    private val maxAttempts: Int = 3,
) {
    init {
        require(maxAttempts in 1..3) { "maxAttempts must be between 1 and 3" }
    }

    fun prepare(
        request: EnvironmentPreparationRequest,
        cancellation: PreparationCancellation = PreparationCancellation(),
        onProgress: (EnvironmentPreparationProgress) -> Unit = {},
    ): EnvironmentPreparationResult {
        val selection = selectArtifacts(request)
        if (selection is SelectionResult.Rejected) {
            cleanupSessionDirectory()
            return failed(selection.failure, selection.detail, onProgress)
        }
        selection as SelectionResult.Accepted
        cleanupSessionDirectory()
        check(privateTemporaryDirectory.mkdirs() || privateTemporaryDirectory.isDirectory) {
            "Unable to create app-private preparation directory"
        }

        val prepared = mutableListOf<PreparedEnvironmentComponent>()
        val technicalDetails = mutableListOf<String>()
        return try {
            selection.artifacts.forEachIndexed { index, selected ->
                cancellation.throwIfCancelled()
                val artifactFilename = requireNotNull(selected.artifact.artifactFilename)
                val destination = File(privateTemporaryDirectory, artifactFilename)
                val receipt = downloadWithRetries(
                    selected = selected,
                    destination = destination,
                    cancellation = cancellation,
                    componentIndex = index + 1,
                    componentCount = selection.artifacts.size,
                    onProgress = onProgress,
                )
                cancellation.throwIfCancelled()
                onProgress(
                    progress(
                        stage = EnvironmentPreparationStage.VERIFYING,
                        index = index + 1,
                        count = selection.artifacts.size,
                        selected = selected,
                        downloaded = receipt.downloadedBytes,
                        total = receipt.expectedBytes,
                        message = "正在进行安全校验",
                    ),
                )
                val verificationFailures = verify(selected.artifact, destination)
                if (verificationFailures.isNotEmpty()) {
                    cleanupSessionDirectory()
                    return failed(
                        failures = verificationFailures,
                        detail = "${selected.artifact.componentId}: ${verificationFailures.joinToString()}",
                        onProgress = onProgress,
                    )
                }
                prepared += PreparedEnvironmentComponent(
                    componentId = selected.artifact.componentId,
                    packageName = selected.artifact.packageName,
                    artifactFilename = artifactFilename,
                    file = destination,
                    sizeBytes = destination.length(),
                )
                technicalDetails += "${selected.artifact.componentId}: hash/package/version/signer verified"
            }
            onProgress(
                EnvironmentPreparationProgress(
                    status = EnvironmentPreparationStatus.DOWNLOAD_VERIFIED_READY,
                    stage = EnvironmentPreparationStage.COMPLETE,
                    componentIndex = selection.artifacts.size,
                    componentCount = selection.artifacts.size,
                    currentComponent = null,
                    downloadedBytes = prepared.sumOf { it.sizeBytes },
                    totalBytes = prepared.sumOf { it.sizeBytes },
                    userMessage = "环境文件已准备完成",
                ),
            )
            EnvironmentPreparationResult(
                status = EnvironmentPreparationStatus.DOWNLOAD_VERIFIED_READY,
                preparedComponents = prepared,
                failures = emptyList(),
                installationAllowed = selection.installationAllowed,
                technicalDetails = technicalDetails,
                catalogVersion = catalogSnapshot?.catalogVersion,
                catalogDigest = catalogSnapshot?.catalogDigest,
            )
        } catch (_: PreparationCancelledException) {
            cleanupSessionDirectory()
            onProgress(
                EnvironmentPreparationProgress(
                    status = EnvironmentPreparationStatus.CANCELLED,
                    stage = EnvironmentPreparationStage.CANCELLED,
                    componentIndex = 0,
                    componentCount = selection.artifacts.size,
                    currentComponent = null,
                    downloadedBytes = 0,
                    totalBytes = null,
                    userMessage = "已取消准备，临时文件已清理",
                ),
            )
            EnvironmentPreparationResult(
                status = EnvironmentPreparationStatus.CANCELLED,
                preparedComponents = emptyList(),
                failures = listOf(EnvironmentPreparationFailure.USER_CANCELLED),
                installationAllowed = false,
                technicalDetails = listOf("Preparation cancelled by user"),
                catalogVersion = catalogSnapshot?.catalogVersion,
                catalogDigest = catalogSnapshot?.catalogDigest,
            )
        } catch (error: DownloadFailedException) {
            cleanupSessionDirectory()
            failed(error.failure, error.message.orEmpty(), onProgress)
        } catch (error: Exception) {
            cleanupSessionDirectory()
            failed(
                EnvironmentPreparationFailure.CATALOG_METADATA_INVALID,
                error.message ?: error::class.java.simpleName,
                onProgress,
            )
        }
    }

    private fun downloadWithRetries(
        selected: SelectedArtifact,
        destination: File,
        cancellation: PreparationCancellation,
        componentIndex: Int,
        componentCount: Int,
        onProgress: (EnvironmentPreparationProgress) -> Unit,
    ): ArtifactDownloadReceipt {
        var lastFailure = EnvironmentPreparationFailure.NETWORK_FAILED
        var lastMessage = "Download failed"
        repeat(maxAttempts) { attemptIndex ->
            cancellation.throwIfCancelled()
            destination.delete()
            try {
                val receipt = downloadTransport.download(
                    sourceUrl = selected.source.downloadUrl!!,
                    destination = destination,
                    cancellation = cancellation,
                ) { downloaded, total ->
                    cancellation.throwIfCancelled()
                    onProgress(
                        progress(
                            stage = EnvironmentPreparationStage.DOWNLOADING,
                            index = componentIndex,
                            count = componentCount,
                            selected = selected,
                            downloaded = downloaded,
                            total = total,
                            message = "下载组件 $componentIndex/$componentCount",
                            detail = "attempt=${attemptIndex + 1}/$maxAttempts",
                        ),
                    )
                }
                val expectedBytes = selected.source.expectedSize ?: receipt.expectedBytes
                if (!destination.isFile || receipt.downloadedBytes <= 0L ||
                    destination.length() != receipt.downloadedBytes ||
                    (expectedBytes != null && receipt.downloadedBytes != expectedBytes)
                ) {
                    lastFailure = EnvironmentPreparationFailure.DOWNLOAD_INCOMPLETE
                    lastMessage = "Downloaded byte count is incomplete"
                    destination.delete()
                } else {
                    return receipt.copy(expectedBytes = expectedBytes)
                }
            } catch (_: PreparationCancelledException) {
                destination.delete()
                throw PreparationCancelledException()
            } catch (error: IOException) {
                lastFailure = EnvironmentPreparationFailure.NETWORK_FAILED
                lastMessage = error.message ?: "Network download failed"
                destination.delete()
            }
        }
        throw DownloadFailedException(lastFailure, lastMessage)
    }

    private fun verify(
        artifact: ComponentArtifact,
        file: File,
    ): List<EnvironmentPreparationFailure> {
        val failures = mutableListOf<EnvironmentPreparationFailure>()
        if (sha256(file) != artifact.sha256?.normalizeDigest()) {
            return listOf(EnvironmentPreparationFailure.SHA256_MISMATCH)
        }
        val metadata = runCatching { apkInspector.inspect(file) }.getOrElse {
            return failures + EnvironmentPreparationFailure.CATALOG_METADATA_INVALID
        }
        if (metadata.packageName != artifact.packageName) {
            failures += EnvironmentPreparationFailure.PACKAGE_NAME_MISMATCH
        }
        if (metadata.versionCode != artifact.artifactVersionCode ||
            metadata.versionName != artifact.artifactVersionName
        ) {
            failures += EnvironmentPreparationFailure.VERSION_MISMATCH
        }
        val actualSigners = metadata.signingCertificateSha256
            .map { it.normalizeDigest() }
            .toSet()
        if (!metadata.signatureVerified ||
            actualSigners != setOf(artifact.signingCertificateDigest.orEmpty().normalizeDigest())
        ) {
            failures += EnvironmentPreparationFailure.SIGNER_MISMATCH
        }
        return failures.distinct()
    }

    private fun selectArtifacts(request: EnvironmentPreparationRequest): SelectionResult {
        val snapshot = catalogSnapshot
            ?: return SelectionResult.Rejected(
                EnvironmentPreparationFailure.CATALOG_NOT_TRUSTED,
                "No trusted catalog snapshot",
            )
        if (request.catalogVersion != snapshot.catalogVersion ||
            request.catalogDigest != snapshot.catalogDigest
        ) {
            return SelectionResult.Rejected(
                EnvironmentPreparationFailure.CATALOG_NOT_TRUSTED,
                "Request catalog identity does not match the fixed snapshot",
            )
        }
        val catalog = snapshot.catalog
        if (request.runtimeEnvironment == RuntimeEnvironment.THIRD_PARTY_COMPAT_RUNTIME) {
            return SelectionResult.Rejected(
                EnvironmentPreparationFailure.THIRD_PARTY_COMPAT_RUNTIME_NOT_ALLOWED,
                "Third-party compatibility runtimes cannot download legacy Huawei components",
            )
        }
        if (request.platformFamily != PlatformFamily.HARMONY_ANDROID_COMPAT ||
            request.deviceCategory == DeviceCategory.HARMONYOS_5_PLUS ||
            request.deviceCategory == DeviceCategory.HARMONY_VERSION_UNKNOWN ||
            request.deviceCategory != DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT ||
            request.planId != CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN
        ) {
            return SelectionResult.Rejected(
                EnvironmentPreparationFailure.DEVICE_BRANCH_NOT_ALLOWED,
                "Legacy Huawei preparation is not allowed for this device branch",
            )
        }
        val systemVersion = request.systemVersion
            ?: return SelectionResult.Rejected(
                EnvironmentPreparationFailure.CATALOG_METADATA_INVALID,
                "HarmonyOS version is required",
            )
        val selection = TrustedComponentCatalogMatcher().select(
            catalog,
            CatalogMatchRequest(
                planId = request.planId,
                deviceCategory = request.deviceCategory,
                deviceFamily = request.deviceModel,
                systemFamily = CatalogSystemFamily.HUAWEI_HARMONY_OS,
                systemVersion = systemVersion,
                androidApiLevel = request.androidApiLevel,
            ),
        )
        val release = selection.compatibleReleases.singleOrNull()
            ?: return SelectionResult.Rejected(
                EnvironmentPreparationFailure.CATALOG_METADATA_INVALID,
                "Expected exactly one explicit Huawei component release",
            )
        val selected = REQUIRED_COMPONENT_IDS.map { componentId ->
            val artifact = release.artifacts.singleOrNull { it.componentId == componentId }
                ?: return SelectionResult.Rejected(
                    EnvironmentPreparationFailure.CATALOG_METADATA_INVALID,
                    "Missing or duplicate artifact: $componentId",
                )
            val source = catalog.sourceRecords.singleOrNull { record ->
                record.matches(release, artifact)
            } ?: return SelectionResult.Rejected(
                EnvironmentPreparationFailure.OFFICIAL_SOURCE_UNAVAILABLE,
                "No unique official GitHub asset for $componentId",
            )
            if (!artifact.hasTrustedDownloadMetadata() ||
                catalog.isBlocked(artifact, request)
            ) {
                return SelectionResult.Rejected(
                    EnvironmentPreparationFailure.CATALOG_METADATA_INVALID,
                    "Artifact metadata is incomplete or blocked: $componentId",
                )
            }
            SelectedArtifact(release, artifact, source)
        }
        val installationAllowed =
            request.validationLevel == GlobalValidationLevel.DEVICE_VERIFIED &&
                request.compatibilityDecisionStatus ==
                CompatibilityDecisionStatus.VERIFIED_WORKFLOW_AVAILABLE &&
                release.compatibilityStatus == CompatibilityValidationStatus.DEVICE_VERIFIED &&
                selected.all {
                    it.artifact.compatibilityStatus ==
                        CompatibilityValidationStatus.DEVICE_VERIFIED
                }
        return SelectionResult.Accepted(selected, installationAllowed)
    }

    private fun ArtifactSourceRecord.matches(
        release: ComponentRelease,
        artifact: ComponentArtifact,
    ): Boolean =
        componentId == artifact.componentId &&
            sourceType == ComponentSourceType.OFFICIAL_MICROG_GITHUB &&
            sourceType == artifact.sourceType &&
            availabilityStatus == SourceAvailabilityStatus.AVAILABLE &&
            sourceAssetId == artifact.githubAssetId &&
            observedFilename == artifact.artifactFilename &&
            sourceDigest?.substringAfter("sha256:", "")?.normalizeDigest() ==
            artifact.sha256?.normalizeDigest() &&
            downloadUrl.isOfficialGitHubAsset(release, artifact)

    private fun String?.isOfficialGitHubAsset(
        release: ComponentRelease,
        artifact: ComponentArtifact,
    ): Boolean {
        val value = this ?: return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.path == "/microg/GmsCore/releases/download/${release.releaseTag}/" +
            artifact.artifactFilename
    }

    private fun ComponentArtifact.hasTrustedDownloadMetadata(): Boolean =
        variant == ComponentVariant.HUAWEI_HW &&
            artifactFilename?.endsWith("-hw.apk") == true &&
            !artifactVersionCode.isNullOrBlank() &&
            !artifactVersionName.isNullOrBlank() &&
            githubAssetId != null &&
            sha256?.let(SHA256::matches) == true &&
            signingCertificateDigest?.let(SHA256::matches) == true &&
            integrityStatus == ArtifactIntegrityStatus.SIGNATURE_VERIFIED &&
            compatibilityStatus !in BLOCKED_COMPATIBILITY_STATUSES

    private fun com.example.globalcompat.catalog.ComponentCatalog.isBlocked(
        artifact: ComponentArtifact,
        request: EnvironmentPreparationRequest,
    ): Boolean =
        blockedVersions.any { rule ->
            rule.componentId == artifact.componentId &&
                (artifact.releaseVersion in rule.versions ||
                    artifact.artifactVersionCode in rule.versions ||
                    artifact.artifactVersionName in rule.versions)
        } || request.systemVersion?.let { it in artifact.blockedSystemVersions } == true

    private fun failed(
        failure: EnvironmentPreparationFailure,
        detail: String,
        onProgress: (EnvironmentPreparationProgress) -> Unit,
    ): EnvironmentPreparationResult = failed(listOf(failure), detail, onProgress)

    private fun failed(
        failures: List<EnvironmentPreparationFailure>,
        detail: String,
        onProgress: (EnvironmentPreparationProgress) -> Unit,
    ): EnvironmentPreparationResult {
        onProgress(
            EnvironmentPreparationProgress(
                status = EnvironmentPreparationStatus.FAIL_CLOSED,
                stage = EnvironmentPreparationStage.FAILED,
                componentIndex = 0,
                componentCount = REQUIRED_COMPONENT_IDS.size,
                currentComponent = null,
                downloadedBytes = 0,
                totalBytes = null,
                userMessage = "准备失败，未保留无效文件",
                technicalDetail = detail,
            ),
        )
        return EnvironmentPreparationResult(
            status = EnvironmentPreparationStatus.FAIL_CLOSED,
            preparedComponents = emptyList(),
            failures = failures.distinct(),
            installationAllowed = false,
            technicalDetails = listOf(detail),
            catalogVersion = catalogSnapshot?.catalogVersion,
            catalogDigest = catalogSnapshot?.catalogDigest,
        )
    }

    private fun progress(
        stage: EnvironmentPreparationStage,
        index: Int,
        count: Int,
        selected: SelectedArtifact,
        downloaded: Long,
        total: Long?,
        message: String,
        detail: String? = null,
    ) = EnvironmentPreparationProgress(
        status = EnvironmentPreparationStatus.PREPARING,
        stage = stage,
        componentIndex = index,
        componentCount = count,
        currentComponent = selected.artifact.componentId,
        downloadedBytes = downloaded,
        totalBytes = total,
        userMessage = message,
        technicalDetail = detail,
    )

    private fun cleanupSessionDirectory() {
        if (!privateTemporaryDirectory.exists()) return
        privateTemporaryDirectory.walkBottomUp().forEach { file ->
            if (file != privateTemporaryDirectory || file.isDirectory) file.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun PreparationCancellation.throwIfCancelled() {
        if (isCancelled()) throw PreparationCancelledException()
    }

    private fun String.normalizeDigest(): String = replace(":", "").lowercase()

    private data class SelectedArtifact(
        val release: ComponentRelease,
        val artifact: ComponentArtifact,
        val source: ArtifactSourceRecord,
    )

    private sealed interface SelectionResult {
        data class Accepted(
            val artifacts: List<SelectedArtifact>,
            val installationAllowed: Boolean,
        ) : SelectionResult

        data class Rejected(
            val failure: EnvironmentPreparationFailure,
            val detail: String,
        ) : SelectionResult
    }

    private class DownloadFailedException(
        val failure: EnvironmentPreparationFailure,
        message: String,
    ) : Exception(message)

    private class PreparationCancelledException : Exception()

    private companion object {
        val REQUIRED_COMPONENT_IDS = listOf(
            "microg_services_huawei_compatible",
            "microg_companion_huawei_compatible",
        )
        val BLOCKED_COMPATIBILITY_STATUSES = setOf(
            CompatibilityValidationStatus.BLOCKED,
            CompatibilityValidationStatus.DEPRECATED,
        )
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
