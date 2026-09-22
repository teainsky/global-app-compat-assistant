package com.example.globalcompat.installation

import com.example.globalcompat.catalog.ArtifactSourceRecord
import com.example.globalcompat.catalog.BuiltInComponentCatalog
import com.example.globalcompat.catalog.CatalogMatchRequest
import com.example.globalcompat.catalog.CatalogSelection
import com.example.globalcompat.catalog.CatalogSystemFamily
import com.example.globalcompat.catalog.ComponentArtifact
import com.example.globalcompat.catalog.ComponentCatalog
import com.example.globalcompat.catalog.ComponentRelease
import com.example.globalcompat.catalog.TrustedComponentCatalogMatcher
import com.example.globalcompat.data.CompatibilityPlanId
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.simulation.CurrentComponentState
import com.example.globalcompat.simulation.SimulatedArtifact
import com.example.globalcompat.simulation.SimulatedInstallationPlan
import com.example.globalcompat.simulation.SimulatedInstallationStep
import com.example.globalcompat.simulation.SimulationNextAction
import com.example.globalcompat.simulation.SimulationPlanStatus

class InstallationExecutionGate(
    private val catalog: ComponentCatalog = BuiltInComponentCatalog.catalog,
) {
    private val gatePolicy = catalog.installationGatePolicy

    fun evaluate(
        simulatedPlan: SimulatedInstallationPlan,
        verificationResults: Map<String, ArtifactVerificationResult> = emptyMap(),
    ): InstallationSessionPlan {
        if ((gatePolicy.blockHarmonyOs5PlusLegacyPlan &&
                simulatedPlan.deviceCategory == DeviceCategory.HARMONYOS_5_PLUS) ||
            simulatedPlan.nextAction == SimulationNextAction.STOP_UNSUPPORTED_SYSTEM
        ) {
            return blockedPlan(
                simulatedPlan = simulatedPlan,
                reasons = listOf(InstallationBlockReason.HARMONYOS_5_PLUS_NOT_SUPPORTED),
            )
        }
        if (simulatedPlan.status == SimulationPlanStatus.NO_ACTION_REQUIRED) {
            val noActionEvidenceIsValid =
                simulatedPlan.compatibilityPlanId == CompatibilityPlanId.NO_ACTION_REQUIRED ||
                    simulatedPlan.selectedArtifacts.isNotEmpty() &&
                    simulatedPlan.currentComponents.isNotEmpty() &&
                    simulatedPlan.currentComponents.all {
                        it.state == CurrentComponentState.OFFICIAL_ARTIFACT_MATCH
                    }
            if (!noActionEvidenceIsValid) {
                return blockedPlan(
                    simulatedPlan = simulatedPlan,
                    reasons = listOf(InstallationBlockReason.SIMULATION_PLAN_BLOCKED),
                )
            }
            return InstallationSessionPlan(
                schemaVersion = SCHEMA_VERSION,
                status = InstallationSessionStatus.NO_ACTION_REQUIRED,
                executionAllowed = false,
                userConfirmationRequired = false,
                userMessage = "当前组件已经安装完成，无需执行安装。",
                steps = simulatedPlan.selectedArtifacts.map { artifact ->
                    completedStep(artifact)
                },
                blockReasons = emptyList(),
            )
        }

        val branchBlock = branchBlockReason(simulatedPlan)
        if (branchBlock != null) {
            return blockedPlan(
                simulatedPlan = simulatedPlan,
                reasons = listOf(branchBlock),
            )
        }

        val simulationBlock = simulationBlockReason(simulatedPlan)
        val catalogSelection = selectCatalog(simulatedPlan)
        if (simulationBlock != null) {
            return blockedPlan(
                simulatedPlan = simulatedPlan,
                reasons = listOf(simulationBlock) +
                    compatibilityBlockReasons(simulatedPlan, catalogSelection),
            )
        }

        val installationByComponent = simulatedPlan.installationOrder.associateBy {
            it.componentId
        }
        val currentByComponent = simulatedPlan.currentComponents.associateBy {
            it.componentId
        }
        val steps = simulatedPlan.selectedArtifacts.map { selectedArtifact ->
            val current = currentByComponent[selectedArtifact.componentId]
            val installation = installationByComponent[selectedArtifact.componentId]
            when {
                current?.state == CurrentComponentState.OFFICIAL_ARTIFACT_MATCH &&
                    installation == null -> completedStep(selectedArtifact)
                current?.state == CurrentComponentState.COMPATIBILITY_SIGNATURE_REPORTED &&
                    installation == null -> blockedCurrentArtifactStep(selectedArtifact)
                installation != null -> evaluateInstallationStep(
                    selectedArtifact = selectedArtifact,
                    installation = installation,
                    verification = verificationResults[selectedArtifact.componentId],
                    compatibleReleases = catalogSelection.compatibleReleases,
                )
                else -> blockedCurrentArtifactStep(selectedArtifact)
            }
        }

        val missingSelectedComponents = simulatedPlan.installationOrder
            .map { it.componentId }
            .filterNot { componentId ->
                simulatedPlan.selectedArtifacts.any { it.componentId == componentId }
            }
        val completeSteps = steps + missingSelectedComponents.map { componentId ->
            InstallationSessionStep(
                componentId = componentId,
                packageName = "",
                action = installationByComponent[componentId]?.action,
                order = installationByComponent[componentId]?.order,
                state = InstallationStepState.BLOCKED,
                downloadRequest = null,
                verificationResult = verificationResults[componentId],
                blockReasons = listOf(InstallationBlockReason.CATALOG_METADATA_INCOMPLETE),
            )
        }
        val blockReasons = completeSteps.flatMap { it.blockReasons }.distinct()
        if (blockReasons.isNotEmpty()) {
            return InstallationSessionPlan(
                schemaVersion = SCHEMA_VERSION,
                status = InstallationSessionStatus.BLOCKED,
                executionAllowed = false,
                userConfirmationRequired = false,
                userMessage = blockMessage(blockReasons),
                steps = completeSteps,
                blockReasons = blockReasons,
            )
        }

        val executableSteps = completeSteps.filter {
            it.state == InstallationStepState.READY_FOR_USER_CONFIRMATION
        }
        if (executableSteps.isEmpty()) {
            return blockedPlan(
                simulatedPlan = simulatedPlan,
                reasons = listOf(InstallationBlockReason.SIMULATION_PLAN_BLOCKED),
            )
        }
        return InstallationSessionPlan(
            schemaVersion = SCHEMA_VERSION,
            status = InstallationSessionStatus.READY_FOR_USER_CONFIRMATION,
            executionAllowed = true,
            userConfirmationRequired = true,
            userMessage = "所有门禁已通过；未来实现仍必须由 Android 系统请求用户确认。",
            steps = completeSteps,
            blockReasons = emptyList(),
        )
    }

    private fun evaluateInstallationStep(
        selectedArtifact: SimulatedArtifact,
        installation: SimulatedInstallationStep,
        verification: ArtifactVerificationResult?,
        compatibleReleases: List<ComponentRelease>,
    ): InstallationSessionStep {
        val matches = compatibleReleases.flatMap { release ->
            release.artifacts
                .filter { it.componentId == selectedArtifact.componentId }
                .map { release to it }
        }
        val (release, artifact) = matches.singleOrNull()
            ?: return InstallationSessionStep(
                componentId = selectedArtifact.componentId,
                packageName = selectedArtifact.packageName,
                action = installation.action,
                order = installation.order,
                state = InstallationStepState.BLOCKED,
                downloadRequest = null,
                verificationResult = verification,
                blockReasons = listOf(InstallationBlockReason.CATALOG_METADATA_INCOMPLETE),
            )

        val source = exactOfficialSource(artifact)
        val reasons = mutableListOf<InstallationBlockReason>()
        if (source == null) reasons += InstallationBlockReason.OFFICIAL_SOURCE_UNAVAILABLE
        if (gatePolicy.requireSha256 && !artifact.sha256.isSha256()) {
            reasons += InstallationBlockReason.SHA256_NOT_AUDITED
        }
        if (gatePolicy.requireSigningCertificate && !artifact.signingCertificateDigest.isSha256()) {
            reasons += InstallationBlockReason.SIGNATURE_NOT_AUDITED
        }
        if (artifact.integrityStatus != gatePolicy.requiredIntegrityStatus) {
            reasons += InstallationBlockReason.ARTIFACT_INTEGRITY_NOT_SIGNATURE_VERIFIED
        }
        if (release.compatibilityStatus != gatePolicy.requiredCompatibilityStatus ||
            artifact.compatibilityStatus != gatePolicy.requiredCompatibilityStatus
        ) {
            reasons += InstallationBlockReason.COMPATIBILITY_NOT_DEVICE_VERIFIED
        }
        if (installation.action == com.example.globalcompat.simulation.SimulatedInstallAction.REPLACE_VERSION) {
            reasons += InstallationBlockReason.VERSION_CONFLICT_REQUIRES_RESOLUTION
        }

        val request = createDownloadRequest(release, artifact, source)
        if (request == null) {
            reasons += InstallationBlockReason.CATALOG_METADATA_INCOMPLETE
        }
        if (verification == null || !verification.downloadEvidencePresent) {
            reasons += InstallationBlockReason.DOWNLOAD_EVIDENCE_MISSING
        } else if (request != null) {
            reasons += verifyDownloadedArtifact(request, verification)
        }

        return InstallationSessionStep(
            componentId = selectedArtifact.componentId,
            packageName = selectedArtifact.packageName,
            action = installation.action,
            order = installation.order,
            state = if (reasons.isEmpty()) {
                InstallationStepState.READY_FOR_USER_CONFIRMATION
            } else {
                InstallationStepState.BLOCKED
            },
            downloadRequest = request,
            verificationResult = verification,
            blockReasons = reasons.distinct(),
        )
    }

    private fun exactOfficialSource(artifact: ComponentArtifact): ArtifactSourceRecord? {
        val matches = catalog.sourceRecords.filter { source ->
            source.componentId == artifact.componentId &&
                source.sourceType == artifact.sourceType &&
                source.sourceType in catalog.verificationPolicy.allowedSourceTypes &&
                source.availabilityStatus == gatePolicy.requiredSourceAvailability &&
                source.sourceAssetId == artifact.githubAssetId &&
                source.observedFilename == artifact.artifactFilename &&
                source.sourceDigest?.substringAfter("sha256:", "")?.normalizeDigest() ==
                artifact.sha256?.normalizeDigest() &&
                !source.downloadUrl.isNullOrBlank()
        }
        return matches.singleOrNull()
    }

    private fun createDownloadRequest(
        release: ComponentRelease,
        artifact: ComponentArtifact,
        source: ArtifactSourceRecord?,
    ): ArtifactDownloadRequest? {
        source ?: return null
        return ArtifactDownloadRequest(
            componentId = artifact.componentId,
            packageName = artifact.packageName,
            releaseTag = release.releaseTag.takeIf { it.isNotBlank() } ?: return null,
            artifactFilename = artifact.artifactFilename ?: return null,
            artifactVersionCode = artifact.artifactVersionCode ?: return null,
            artifactVersionName = artifact.artifactVersionName ?: return null,
            sourceType = source.sourceType,
            sourceUrl = source.downloadUrl ?: return null,
            sourceAssetId = source.sourceAssetId ?: return null,
            expectedSha256 = artifact.sha256 ?: return null,
            expectedSigningCertificateSha256 =
                artifact.signingCertificateDigest ?: return null,
        )
    }

    private fun verifyDownloadedArtifact(
        request: ArtifactDownloadRequest,
        verification: ArtifactVerificationResult,
    ): List<InstallationBlockReason> = buildList {
        if (verification.componentId != request.componentId ||
            verification.sourceAssetId != request.sourceAssetId ||
            verification.artifactFilename != request.artifactFilename
        ) {
            add(InstallationBlockReason.DOWNLOADED_ARTIFACT_SOURCE_MISMATCH)
        }
        if (verification.locallyCalculatedSha256?.normalizeDigest() !=
            request.expectedSha256.normalizeDigest()
        ) {
            add(InstallationBlockReason.SHA256_MISMATCH)
        }
        val actualSigners = verification.signingCertificateSha256
            .map { it.normalizeDigest() }
            .toSet()
        if (!verification.apkSignatureVerificationPassed ||
            actualSigners != setOf(request.expectedSigningCertificateSha256.normalizeDigest())
        ) {
            add(InstallationBlockReason.SIGNATURE_MISMATCH)
        }
        if (gatePolicy.requirePackageNameMatch && verification.packageName != request.packageName) {
            add(InstallationBlockReason.PACKAGE_NAME_MISMATCH)
        }
        if (gatePolicy.requireVersionMatch &&
            (verification.versionCode != request.artifactVersionCode ||
                verification.versionName != request.artifactVersionName)
        ) {
            add(InstallationBlockReason.VERSION_MISMATCH)
        }
    }

    private fun branchBlockReason(
        simulatedPlan: SimulatedInstallationPlan,
    ): InstallationBlockReason? = when {
        simulatedPlan.deviceCategory !in gatePolicy.allowedDeviceCategories ||
            simulatedPlan.compatibilityPlanId != CompatibilityPlanId.HUAWEI_MICROG_COMPAT_PLAN ->
            InstallationBlockReason.DEVICE_BRANCH_NOT_ALLOWED
        else -> null
    }

    private fun simulationBlockReason(
        simulatedPlan: SimulatedInstallationPlan,
    ): InstallationBlockReason? {
        if (simulatedPlan.status != SimulationPlanStatus.BLOCKED &&
            simulatedPlan.status != SimulationPlanStatus.REVIEW_REQUIRED
        ) {
            return null
        }
        return when (simulatedPlan.nextAction) {
            SimulationNextAction.STOP_SIGNATURE_MISMATCH ->
                InstallationBlockReason.SIGNATURE_MISMATCH
            SimulationNextAction.STOP_OFFICIAL_COMPONENT_UNAVAILABLE ->
                InstallationBlockReason.OFFICIAL_SOURCE_UNAVAILABLE
            SimulationNextAction.STOP_INTEGRITY_EVIDENCE_INCOMPLETE ->
                InstallationBlockReason.ARTIFACT_INTEGRITY_NOT_SIGNATURE_VERIFIED
            SimulationNextAction.VERIFY_CURRENT_ARTIFACT ->
                InstallationBlockReason.CURRENT_ARTIFACT_NOT_VERIFIED
            else -> InstallationBlockReason.SIMULATION_PLAN_BLOCKED
        }
    }

    private fun completedStep(
        artifact: SimulatedArtifact,
    ) = InstallationSessionStep(
        componentId = artifact.componentId,
        packageName = artifact.packageName,
        action = null,
        order = null,
        state = InstallationStepState.ALREADY_COMPLETED,
        downloadRequest = null,
        verificationResult = null,
        blockReasons = emptyList(),
    )

    private fun blockedCurrentArtifactStep(
        artifact: SimulatedArtifact,
    ) = InstallationSessionStep(
        componentId = artifact.componentId,
        packageName = artifact.packageName,
        action = null,
        order = null,
        state = InstallationStepState.BLOCKED,
        downloadRequest = null,
        verificationResult = null,
        blockReasons = listOf(InstallationBlockReason.CURRENT_ARTIFACT_NOT_VERIFIED),
    )

    private fun blockedPlan(
        simulatedPlan: SimulatedInstallationPlan,
        reasons: List<InstallationBlockReason>,
    ) = InstallationSessionPlan(
        schemaVersion = SCHEMA_VERSION,
        status = InstallationSessionStatus.BLOCKED,
        executionAllowed = false,
        userConfirmationRequired = false,
        userMessage = blockMessage(reasons),
        steps = simulatedPlan.selectedArtifacts.map { artifact ->
            InstallationSessionStep(
                componentId = artifact.componentId,
                packageName = artifact.packageName,
                action = simulatedPlan.installationOrder
                    .firstOrNull { it.componentId == artifact.componentId }
                    ?.action,
                order = simulatedPlan.installationOrder
                    .firstOrNull { it.componentId == artifact.componentId }
                    ?.order,
                state = InstallationStepState.BLOCKED,
                downloadRequest = null,
                verificationResult = null,
                blockReasons = reasons,
            )
        },
        blockReasons = reasons.distinct(),
    )

    private fun compatibilityBlockReasons(
        simulatedPlan: SimulatedInstallationPlan,
        selection: CatalogSelection,
    ): List<InstallationBlockReason> {
        val selectedIds = simulatedPlan.selectedArtifacts.map { it.componentId }.toSet()
        if (selectedIds.isEmpty()) return emptyList()
        val release = selection.recommendedRelease
        val recommended = selection.recommendedArtifacts.filter { it.componentId in selectedIds }
        val isDeviceVerified = release?.compatibilityStatus ==
            gatePolicy.requiredCompatibilityStatus &&
            recommended.size == selectedIds.size &&
            recommended.all {
                it.compatibilityStatus == gatePolicy.requiredCompatibilityStatus
            }
        return if (isDeviceVerified) {
            emptyList()
        } else {
            listOf(InstallationBlockReason.COMPATIBILITY_NOT_DEVICE_VERIFIED)
        }
    }

    private fun selectCatalog(simulatedPlan: SimulatedInstallationPlan): CatalogSelection =
        TrustedComponentCatalogMatcher().select(
            catalog,
            CatalogMatchRequest(
                planId = simulatedPlan.compatibilityPlanId,
                deviceCategory = simulatedPlan.deviceCategory,
                deviceFamily = simulatedPlan.deviceModel,
                systemFamily = CatalogSystemFamily.HUAWEI_HARMONY_OS,
                systemVersion = simulatedPlan.systemVersion,
                androidApiLevel = simulatedPlan.androidApiLevel,
            ),
        )

    private fun blockMessage(reasons: List<InstallationBlockReason>): String = when {
        InstallationBlockReason.HARMONYOS_5_PLUS_NOT_SUPPORTED in reasons ->
            "HarmonyOS 5+ 不进入旧鸿蒙安装流程"
        InstallationBlockReason.SIGNATURE_MISMATCH in reasons ->
            "组件签名异常，安装已被安全门禁阻止"
        InstallationBlockReason.OFFICIAL_SOURCE_UNAVAILABLE in reasons ->
            "官方组件不可取得，安装已被安全门禁阻止"
        InstallationBlockReason.VERSION_CONFLICT_REQUIRES_RESOLUTION in reasons ->
            "检测到版本冲突，暂不可执行替换"
        InstallationBlockReason.COMPATIBILITY_NOT_DEVICE_VERIFIED in reasons ->
            "当前方案尚未完成设备验证，暂不可安装"
        InstallationBlockReason.DOWNLOAD_EVIDENCE_MISSING in reasons ->
            "缺少下载与本地校验证据，暂不可安装"
        else -> "安装前置证据不足，已安全停止"
    }

    private fun String.normalizeDigest(): String = replace(":", "").lowercase()

    private fun String?.isSha256(): Boolean =
        this != null && SHA256.matches(normalizeDigest())

    private companion object {
        const val SCHEMA_VERSION = 1
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
