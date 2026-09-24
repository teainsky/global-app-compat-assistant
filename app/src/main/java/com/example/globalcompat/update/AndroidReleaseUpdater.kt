package com.example.globalcompat.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.annotation.RequiresApi
import com.example.globalcompat.BuildConfig
import com.example.globalcompat.MainActivity
import com.example.globalcompat.R
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

class AndroidReleaseUpdater(private val context: Context) {
    private val store = SharedPreferencesUpdateCheckStateStore(context)
    private val source = GitHubLatestReleaseSource(BuildConfig.GITHUB_RELEASE_API_URL)

    fun initialState(currentVersionName: String): ReleaseUpdateUiState =
        store.cachedRelease()
            ?.takeIf { ReleaseVersionComparator.isNewer(it.versionName, currentVersionName) }
            ?.let { ReleaseUpdateUiState(ReleaseUpdateStatus.UPDATE_AVAILABLE, it) }
            ?: ReleaseUpdateUiState()

    fun checkIfDue(currentVersionName: String): ReleaseUpdateUiState =
        DailyReleaseUpdateChecker(store, source).checkIfDue(currentVersionName)

    fun downloadAndVerify(
        release: AvailableRelease,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Pair<File, VerifiedReleaseApk> {
        val updateDirectory = File(context.filesDir, UPDATE_DIRECTORY)
        check(updateDirectory.mkdirs() || updateDirectory.isDirectory) {
            "Unable to create private update directory"
        }
        val partial = File(context.cacheDir, "app-update.partial")
        val verified = File(updateDirectory, VERIFIED_APK_NAME)
        partial.delete()
        verified.delete()
        try {
            download(release.asset, partial, onProgress)
            val localSha256 = partial.sha256()
            val metadata = inspect(partial).copy(sha256 = localSha256)
            ReleaseApkVerificationPolicy.requireValid(
                release = release,
                expectedPackageName = context.packageName,
                currentVersionCode = currentVersionCode(),
                expectedSignerSha256 = RELEASE_SIGNER_SHA256,
                actual = metadata,
            )
            check(partial.renameTo(verified)) { "Unable to retain verified update APK" }
            return verified to metadata
        } catch (failure: Throwable) {
            partial.delete()
            verified.delete()
            throw failure
        }
    }

    fun installIntent(apk: File): Intent {
        check(apk.isFile && apk.name == VERIFIED_APK_NAME) { "Verified update APK is missing" }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.update-files",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    @RequiresApi(Build.VERSION_CODES.O)
    fun installPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("DEPRECATION")
    private fun inspect(apk: File): VerifiedReleaseApk {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Android could not parse the downloaded APK")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        check(signatures.size == 1) { "Downloaded APK must have exactly one current signer" }
        return VerifiedReleaseApk(
            packageName = info.packageName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            versionName = info.versionName.orEmpty(),
            sha256 = "",
            signerSha256 = signatures.single().toByteArray().sha256(),
        )
    }

    private fun download(
        asset: ReleaseApkAsset,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val uri = URI.create(asset.downloadUrl)
        check(uri.scheme.equals("https", true) && uri.host.equals("github.com", true)) {
            "Only official GitHub release assets are allowed"
        }
        var lastFailure: Throwable? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            destination.delete()
            val result = runCatching {
                val connection = URL(asset.downloadUrl).openConnection() as HttpsURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.setRequestProperty("Accept", "application/octet-stream")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                try {
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "GitHub download returned HTTP ${connection.responseCode}"
                    }
                    var downloaded = 0L
                    connection.inputStream.buffered().use { input ->
                        destination.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                check(downloaded <= MAX_APK_BYTES) { "Downloaded APK was too large" }
                                onProgress(downloaded, asset.sizeBytes)
                            }
                        }
                    }
                    check(downloaded == asset.sizeBytes) { "Downloaded APK was incomplete" }
                } finally {
                    connection.disconnect()
                }
            }
            if (result.isSuccess) return
            lastFailure = result.exceptionOrNull()
            destination.delete()
            if (attempt + 1 < MAX_DOWNLOAD_ATTEMPTS) Thread.sleep(RETRY_DELAY_MILLIS)
        }
        throw IOException("GitHub APK download failed after limited retries", lastFailure)
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info)
    }

    companion object {
        const val RELEASE_SIGNER_SHA256 =
            "14c8e6cc6806941cda9e0b48d60316e9ff8b9dfd7be98b94ed5a41ce2290231a"
        private const val UPDATE_DIRECTORY = "updates"
        private const val VERIFIED_APK_NAME = "app-release.apk"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 60_000
        private const val RETRY_DELAY_MILLIS = 500L
        private const val MAX_APK_BYTES = 250L * 1024L * 1024L
        private const val USER_AGENT = "global-app-compat-assistant-update-checker"
    }
}

class GitHubLatestReleaseSource(private val apiUrl: String) : LatestReleaseSource {
    override fun fetchLatest(): AvailableRelease {
        val uri = URI.create(apiUrl.ifBlank { throw IOException("GitHub release API is not configured") })
        check(uri.scheme.equals("https", true) && uri.host.equals("api.github.com", true)) {
            "Release metadata must come from api.github.com"
        }
        check(RELEASE_API_PATH.matches(uri.path)) { "GitHub API URL must target releases/latest" }
        val connection = URL(apiUrl).openConnection() as HttpsURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "global-app-compat-assistant-update-checker")
        return try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "GitHub release API returned HTTP ${connection.responseCode}"
            }
            GitHubLatestReleaseParser.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val RELEASE_API_PATH = Regex("^/repos/[^/]+/[^/]+/releases/latest$")
    }
}

private class SharedPreferencesUpdateCheckStateStore(context: Context) : UpdateCheckStateStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    override fun lastAttemptAt(): Long? = preferences.getLong(KEY_LAST_ATTEMPT, -1L)
        .takeIf { it >= 0L }

    override fun recordAttempt(atMillis: Long) {
        preferences.edit().putLong(KEY_LAST_ATTEMPT, atMillis).apply()
    }

    override fun cachedRelease(): AvailableRelease? = preferences.getString(KEY_RELEASE, null)
        ?.let { json -> runCatching { gson.fromJson(json, AvailableRelease::class.java) }.getOrNull() }

    override fun saveRelease(release: AvailableRelease?) {
        preferences.edit().apply {
            if (release == null) remove(KEY_RELEASE) else putString(KEY_RELEASE, gson.toJson(release))
        }.apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "release-update-state"
        const val KEY_LAST_ATTEMPT = "last-attempt-at"
        const val KEY_RELEASE = "available-release"
    }
}

object ReleaseUpdateNotification {
    const val EXTRA_OPEN_UPDATE = "open-release-update"
    private const val CHANNEL_ID = "release-updates"
    private const val NOTIFICATION_ID = 1_006

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun show(context: Context, release: AvailableRelease) {
        if (!hasPermission(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "版本更新",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "发现新的正式版本时提醒"
                    setShowBadge(true)
                },
            )
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_UPDATE, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("发现新版本")
            .setContentText("${release.versionName} 已可更新")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setNumber(1)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the explicit check and this call.
        }
    }
}

private fun File.sha256(): String = inputStream().buffered().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(input, digest).use { stream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (stream.read(buffer) >= 0) Unit
    }
    digest.digest().toHex()
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
}
