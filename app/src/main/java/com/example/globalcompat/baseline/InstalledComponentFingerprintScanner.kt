package com.example.globalcompat.baseline

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.security.MessageDigest

data class InstalledPackageMetadata(
    val enabled: Boolean,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val reportedSigningCertificateSha256: List<String>,
    val installSource: String?,
)

fun interface InstalledPackageLookup {
    fun read(packageName: String): InstalledPackageMetadata?
}

class InstalledComponentFingerprintScanner(
    private val lookup: InstalledPackageLookup,
) {
    fun scan(): List<InstalledComponentFingerprint> = TARGET_PACKAGES.map(::scanPackage)

    private fun scanPackage(packageName: String): InstalledComponentFingerprint = try {
        val metadata = lookup.read(packageName)
            ?: return notInstalled(packageName)
        InstalledComponentFingerprint(
            installed = true,
            enabled = metadata.enabled,
            packageName = metadata.packageName,
            versionCode = metadata.versionCode,
            versionName = metadata.versionName,
            reportedSigningCertificateSha256 = metadata.reportedSigningCertificateSha256,
            installSource = metadata.installSource,
            readStatus = ComponentFingerprintReadStatus.READABLE,
        )
    } catch (_: Exception) {
        InstalledComponentFingerprint(
            installed = false,
            enabled = null,
            packageName = packageName,
            versionCode = null,
            versionName = null,
            reportedSigningCertificateSha256 = emptyList(),
            installSource = null,
            readStatus = ComponentFingerprintReadStatus.UNREADABLE,
        )
    }

    private fun notInstalled(packageName: String) = InstalledComponentFingerprint(
        installed = false,
        enabled = null,
        packageName = packageName,
        versionCode = null,
        versionName = null,
        reportedSigningCertificateSha256 = emptyList(),
        installSource = null,
        readStatus = ComponentFingerprintReadStatus.NOT_INSTALLED,
    )

    companion object {
        val TARGET_PACKAGES = listOf("com.google.android.gms", "com.android.vending")
    }
}

class AndroidInstalledPackageLookup(
    private val packageManager: PackageManager,
) : InstalledPackageLookup {
    override fun read(packageName: String): InstalledPackageMetadata? {
        val packageInfo = try {
            getPackageInfo(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return InstalledPackageMetadata(
            enabled = packageInfo.applicationInfo?.enabled == true,
            packageName = packageInfo.packageName,
            versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
            versionName = packageInfo.versionName,
            reportedSigningCertificateSha256 = currentSignatures(packageInfo)
                .map { signature -> sha256(signature.toByteArray()) }
                .distinct()
                .sorted(),
            installSource = readInstallSource(packageName),
        )
    }

    private fun getPackageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                packageName,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                },
            )
        }

    private fun currentSignatures(packageInfo: PackageInfo) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures ?: emptyArray()
        }

    private fun readInstallSource(packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val source = packageManager.getInstallSourceInfo(packageName)
            source.installingPackageName
                ?: source.initiatingPackageName
                ?: source.originatingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
