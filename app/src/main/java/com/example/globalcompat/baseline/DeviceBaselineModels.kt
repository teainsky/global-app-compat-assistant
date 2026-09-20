package com.example.globalcompat.baseline

import com.example.globalcompat.catalog.InstalledArtifactSignatureStatus

enum class ComponentFingerprintReadStatus {
    READABLE,
    NOT_INSTALLED,
    UNREADABLE,
}

data class InstalledComponentFingerprint(
    val installed: Boolean,
    val enabled: Boolean?,
    val packageName: String,
    val versionCode: Long?,
    val versionName: String?,
    val reportedSigningCertificateSha256: List<String>,
    val installSource: String?,
    val readStatus: ComponentFingerprintReadStatus,
)

enum class OfficialComponentMatchStatus {
    VERSION_MATCH,
    VERSION_MISMATCH,
    NOT_INSTALLED,
    UNREADABLE,
    UNKNOWN,
}

data class OfficialComponentComparison(
    val componentId: String?,
    val fingerprint: InstalledComponentFingerprint,
    val status: OfficialComponentMatchStatus,
    val signatureStatus: InstalledArtifactSignatureStatus,
    val expectedAssetId: Long?,
    val expectedVersionCode: Long?,
    val expectedVersionName: String?,
    val expectedSignerSha256: String?,
)

enum class UserValidationAnswer {
    UNANSWERED,
    YES,
    NO,
}

data class UserFunctionalValidation(
    val googleAccountLogin: UserValidationAnswer = UserValidationAnswer.UNANSWERED,
    val chatGptLoginAndUse: UserValidationAnswer = UserValidationAnswer.UNANSWERED,
    val chromeGoogleLogin: UserValidationAnswer = UserValidationAnswer.UNANSWERED,
) {
    fun allSuccessful(): Boolean =
        googleAccountLogin == UserValidationAnswer.YES &&
            chatGptLoginAndUse == UserValidationAnswer.YES &&
            chromeGoogleLogin == UserValidationAnswer.YES
}

data class BaselineDeviceInfo(
    val model: String,
)

data class BaselineSystemInfo(
    val harmonyOsVersion: String?,
    val androidVersion: String,
    val androidApiLevel: Int,
    val romFamily: String,
    val romDisplayName: String,
    val romVersion: String?,
)

data class BaselineComponentReport(
    val installed: Boolean,
    val enabled: Boolean?,
    val packageName: String,
    val versionCode: Long?,
    val versionName: String?,
    val reportedSigningCertificateSha256: List<String>,
    val installSource: String?,
    val officialMatchStatus: OfficialComponentMatchStatus,
    val signatureStatus: InstalledArtifactSignatureStatus,
)

data class DeviceValidationRecord(
    val schemaVersion: Int,
    val validatedAtEpochMillis: Long,
    val deviceModel: String,
    val harmonyOsVersion: String?,
    val androidVersion: String,
    val androidApiLevel: Int,
    val romFamily: String,
    val matchedPackages: List<String>,
    val functionalValidation: UserFunctionalValidation,
)

data class DeviceBaselineReport(
    val schemaVersion: Int,
    val capturedAtEpochMillis: Long,
    val device: BaselineDeviceInfo,
    val system: BaselineSystemInfo,
    val components: List<BaselineComponentReport>,
    val functionalValidation: UserFunctionalValidation,
    val deviceValidationRecord: DeviceValidationRecord?,
)
