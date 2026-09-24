package com.example.globalcompat.data

enum class FreeMvpCapability {
    DETECTION,
    GOOGLE_DIAGNOSTICS,
    VERIFIED_CONFIGURATION,
}

enum class FreeMvpConfigurationStatus {
    VERIFIED_AVAILABLE,
    NOT_VERIFIED,
    WORKFLOW_NOT_APPLICABLE,
}

data class FreeMvpCoverage(
    val capabilities: List<FreeMvpCapability>,
    val configurationStatus: FreeMvpConfigurationStatus,
)

class FreeMvpCoveragePolicy {
    fun evaluate(
        profile: DeviceProfile,
        verifiedWorkflow: ApplicableWorkflow?,
    ): FreeMvpCoverage {
        val verifiedConfigurationAvailable =
            profile.validationLevel == GlobalValidationLevel.DEVICE_VERIFIED &&
                profile.platformFamily == PlatformFamily.HARMONY_ANDROID_COMPAT &&
                verifiedWorkflow == ApplicableWorkflow.HUAWEI_MICROG_COMPAT
        val configurationStatus = when {
            verifiedConfigurationAvailable -> FreeMvpConfigurationStatus.VERIFIED_AVAILABLE
            profile.platformFamily == PlatformFamily.HARMONY_NATIVE ||
                profile.platformFamily == PlatformFamily.HARMONY_VERSION_UNKNOWN ->
                FreeMvpConfigurationStatus.WORKFLOW_NOT_APPLICABLE
            else -> FreeMvpConfigurationStatus.NOT_VERIFIED
        }
        return FreeMvpCoverage(
            capabilities = buildList {
                add(FreeMvpCapability.DETECTION)
                add(FreeMvpCapability.GOOGLE_DIAGNOSTICS)
                if (verifiedConfigurationAvailable) {
                    add(FreeMvpCapability.VERIFIED_CONFIGURATION)
                }
            },
            configurationStatus = configurationStatus,
        )
    }
}
