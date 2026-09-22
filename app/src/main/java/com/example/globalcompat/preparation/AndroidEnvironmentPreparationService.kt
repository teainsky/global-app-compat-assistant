package com.example.globalcompat.preparation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.example.globalcompat.catalog.CatalogSnapshot
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

class AndroidEnvironmentPreparationService(
    private val context: Context,
) {
    fun prepare(
        request: EnvironmentPreparationRequest,
        catalogSnapshot: CatalogSnapshot?,
        cancellation: PreparationCancellation,
        onProgress: (EnvironmentPreparationProgress) -> Unit,
    ): EnvironmentPreparationResult = EnvironmentPreparationCoordinator(
        catalogSnapshot = catalogSnapshot,
        downloadTransport = HttpsOfficialArtifactDownloadTransport(),
        apkInspector = AndroidDownloadedApkInspector(context.packageManager),
        privateTemporaryDirectory = File(context.cacheDir, PRIVATE_DIRECTORY_NAME),
    ).prepare(request, cancellation, onProgress)

    private companion object {
        const val PRIVATE_DIRECTORY_NAME = "official-component-preparation"
    }
}

class HttpsOfficialArtifactDownloadTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 60_000,
) : OfficialArtifactDownloadTransport {
    override fun download(
        sourceUrl: String,
        destination: File,
        cancellation: PreparationCancellation,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): ArtifactDownloadReceipt {
        val connection = URL(sourceUrl).openConnection() as? HttpsURLConnection
            ?: throw IOException("Official source did not use HTTPS")
        connection.instanceFollowRedirects = true
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/octet-stream")
        return try {
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Official source returned HTTP ${connection.responseCode}")
            }
            val contentLength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connection.contentLengthLong
            } else {
                connection.contentLength.toLong()
            }
            val expectedBytes = contentLength.takeIf { it >= 0L }
            destination.parentFile?.let { parent ->
                check(parent.mkdirs() || parent.isDirectory) {
                    "Unable to create private download directory"
                }
            }
            var downloaded = 0L
            connection.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (cancellation.isCancelled()) throw IOException("Download cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, expectedBytes)
                    }
                }
            }
            ArtifactDownloadReceipt(downloaded, expectedBytes)
        } finally {
            connection.disconnect()
        }
    }
}

class AndroidDownloadedApkInspector(
    private val packageManager: PackageManager,
) : DownloadedApkInspector {
    @Suppress("DEPRECATION")
    override fun inspect(apkFile: File): DownloadedApkMetadata {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val packageInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: error("Android could not parse the downloaded APK")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: error("APK signing information is missing")
            signingInfo.apkContentsSigners
        } else {
            packageInfo.signatures.orEmpty()
        }
        check(signatures.isNotEmpty()) { "APK signing certificate is missing" }
        return DownloadedApkMetadata(
            packageName = packageInfo.packageName,
            versionCode = PackageInfoCompat.getLongVersionCode(packageInfo).toString(),
            versionName = packageInfo.versionName.orEmpty(),
            signingCertificateSha256 = signatures.map { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte) }
            }.distinct(),
            signatureVerified = true,
        )
    }
}
