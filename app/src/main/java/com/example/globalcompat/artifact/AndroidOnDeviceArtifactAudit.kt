package com.example.globalcompat.artifact

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.globalcompat.baseline.AndroidInstalledPackageLookup
import com.example.globalcompat.validation.ValidationDeviceProfile
import com.example.globalcompat.validation.ValidationSystemProfile
import java.io.File
import java.security.MessageDigest

class AndroidInstalledApkPathLookup(
    private val packageManager: PackageManager,
) : InstalledApkPathLookup {
    private val metadataLookup = AndroidInstalledPackageLookup(packageManager)

    override fun read(packageName: String): InstalledApkLookupResult {
        val applicationInfo = try {
            getApplicationInfo(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return InstalledApkLookupResult(InstalledApkLookupStatus.NOT_INSTALLED, null)
        } catch (_: Exception) {
            return InstalledApkLookupResult(InstalledApkLookupStatus.UNREADABLE, null)
        }
        val metadata = runCatching { metadataLookup.read(packageName) }.getOrNull()
            ?: return InstalledApkLookupResult(InstalledApkLookupStatus.UNREADABLE, null)
        return InstalledApkLookupResult(
            status = InstalledApkLookupStatus.INSTALLED,
            descriptor = InstalledApkDescriptor(
                packageName = metadata.packageName,
                versionCode = metadata.versionCode.toString(),
                versionName = metadata.versionName,
                reportedSigningCertificateSha256 =
                    metadata.reportedSigningCertificateSha256,
                baseApkPath = applicationInfo.sourceDir.orEmpty(),
                splitApkPaths = applicationInfo.splitSourceDirs?.toList().orEmpty(),
            ),
        )
    }

    private fun getApplicationInfo(packageName: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
}

class AndroidApkByteDigestReader : ApkByteDigestReader {
    override fun readSha256(path: String): ApkByteDigestResult = try {
        val file = File(path)
        if (!file.isFile) {
            ApkByteDigestResult(false, null, "FileNotAccessible")
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            ApkByteDigestResult(
                readable = true,
                sha256 = digest.digest().joinToString("") { byte ->
                    "%02x".format(byte)
                },
                failureType = null,
            )
        }
    } catch (error: Exception) {
        ApkByteDigestResult(false, null, error::class.java.simpleName)
    }
}

class AndroidOnDeviceArtifactAuditService(
    context: Context,
) {
    private val auditor = OnDeviceArtifactAuditor(
        installedApkLookup = AndroidInstalledApkPathLookup(context.packageManager),
        digestReader = AndroidApkByteDigestReader(),
    )

    fun audit(
        deviceProfile: ValidationDeviceProfile,
        systemProfile: ValidationSystemProfile,
    ): OnDeviceArtifactAuditReport = auditor.audit(
        OnDeviceArtifactAuditRequest(deviceProfile, systemProfile),
    )
}
