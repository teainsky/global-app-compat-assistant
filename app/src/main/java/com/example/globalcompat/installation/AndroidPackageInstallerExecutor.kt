package com.example.globalcompat.installation

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.globalcompat.baseline.AndroidInstalledPackageLookup
import com.example.globalcompat.baseline.OfficialComponentMatcher
import com.example.globalcompat.catalog.RuntimeTrustedCatalogRepository
import com.example.globalcompat.catalog.TrustedCatalogSnapshotProvider
import com.example.globalcompat.data.DeviceCategory
import com.example.globalcompat.data.DeviceEnvironmentScanner
import com.example.globalcompat.data.RomFamily
import com.example.globalcompat.preparation.AndroidDownloadedApkInspector
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal class AndroidKeyStoreInstallAuthorizationSealer : InstallAuthorizationSealer {
    private val delegate = HmacSha256InstallAuthorizationSealer(::key)

    override fun seal(payload: ByteArray): String = delegate.seal(payload)

    override fun verify(payload: ByteArray, proof: String): Boolean =
        delegate.verify(payload, proof)

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEY_STORE,
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                ).setDigests(KeyProperties.DIGEST_SHA256).build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "globalcompat.install-authorization.hmac.v1"
    }
}

internal class AndroidInstallationDeviceContextProvider(
    private val context: Context,
    private val catalogProvider: TrustedCatalogSnapshotProvider,
) : InstallationDeviceContextProvider {
    override fun current(): InstallationDeviceContext {
        val report = DeviceEnvironmentScanner(
            context = context,
            catalogSnapshot = catalogProvider.currentSnapshot(),
        ).scan()
        return InstallationDeviceContext(
            deviceCategory = report.compatibilityPlan.deviceCategory,
            manufacturer = report.device.manufacturer,
            model = report.deviceProfile.model,
            platformFamily = report.deviceProfile.platformFamily,
            osVersion = report.deviceProfile.osVersion,
            androidApiLevel = report.deviceProfile.androidApiLevel,
            romFamily = report.rom.family,
            romVersion = report.rom.version,
        )
    }
}

class SharedPreferencesInstallationSessionStore(
    context: Context,
    private val gson: Gson = Gson(),
) : InstallationSessionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): InstallationExecutionSnapshot? = preferences
        .getString(SESSION_KEY, null)
        ?.let { json ->
            runCatching {
                gson.fromJson(json, InstallationExecutionSnapshot::class.java)
            }.getOrNull()
        }

    override fun save(snapshot: InstallationExecutionSnapshot) {
        check(preferences.edit().putString(SESSION_KEY, gson.toJson(snapshot)).commit()) {
            "Unable to persist installation session"
        }
    }

    override fun clear() {
        check(preferences.edit().remove(SESSION_KEY).commit()) {
            "Unable to clear installation session"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "installation-executor-v1"
        const val SESSION_KEY = "active-session"
    }
}

class AndroidPackageInstallerGateway(
    private val context: Context,
) : PackageInstallerGateway {
    private val packageInstaller = context.packageManager.packageInstaller
    private val allowedDirectory = File(context.cacheDir, "official-component-preparation")

    override fun hasInstallPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            null
        }

    override fun commit(
        artifact: ExecutableInstallationArtifact,
        logicalSessionId: String,
    ): Int {
        check(hasInstallPermission()) { "Install source permission is not granted" }
        val apk = File(artifact.filePath).canonicalFile
        val allowedRoot = allowedDirectory.canonicalFile
        val allowedPathPrefix = allowedRoot.path + File.separator
        check(apk.isFile && apk.path.startsWith(allowedPathPrefix)) {
            "APK is outside the app-private preparation directory"
        }
        check(apk.length() == artifact.expectedSizeBytes) { "Prepared APK size changed" }

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(artifact.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
        val installerSessionId = packageInstaller.createSession(params)
        try {
            packageInstaller.openSession(installerSessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(artifact.artifactFilename, 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callbackIntent = Intent(context, InstallationResultReceiver::class.java)
                    .setAction(InstallationResultReceiver.ACTION_INSTALL_STATUS)
                    .putExtra(
                        InstallationResultReceiver.EXTRA_LOGICAL_SESSION_ID,
                        logicalSessionId,
                    )
                    .putExtra(
                        InstallationResultReceiver.EXTRA_EXPECTED_INSTALLER_SESSION_ID,
                        installerSessionId,
                    )
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val statusReceiver = PendingIntent.getBroadcast(
                    context,
                    installerSessionId,
                    callbackIntent,
                    flags,
                )
                session.commit(statusReceiver.intentSender)
            }
            return installerSessionId
        } catch (error: Exception) {
            runCatching { packageInstaller.abandonSession(installerSessionId) }
            throw error
        }
    }

    override fun isSessionActive(packageInstallerSessionId: Int): Boolean =
        packageInstaller.getSessionInfo(packageInstallerSessionId) != null
}

class AndroidPreparedArtifactRevalidator(
    context: Context,
) : PreparedArtifactRevalidator {
    private val inspector = AndroidDownloadedApkInspector(context.packageManager)

    override fun validate(
        artifact: ExecutableInstallationArtifact,
    ): PreparedArtifactValidation {
        val file = File(artifact.filePath)
        if (!file.isFile || file.length() != artifact.expectedSizeBytes) {
            return PreparedArtifactValidation(false, "Prepared APK is missing or changed")
        }
        if (sha256(file) != artifact.expectedSha256.normalizeDigest()) {
            return PreparedArtifactValidation(false, "SHA-256 mismatch")
        }
        val metadata = runCatching { inspector.inspect(file) }.getOrElse {
            return PreparedArtifactValidation(false, "APK metadata or signature is unreadable")
        }
        val signers = metadata.signingCertificateSha256.map { it.normalizeDigest() }.toSet()
        val matches = metadata.signatureVerified &&
            metadata.packageName == artifact.packageName &&
            metadata.versionCode == artifact.expectedVersionCode &&
            metadata.versionName == artifact.expectedVersionName &&
            signers == setOf(artifact.expectedSigningCertificateSha256.normalizeDigest())
        return PreparedArtifactValidation(
            matches = matches,
            detail = if (matches) "hash/package/version/signer verified" else "metadata mismatch",
        )
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
}

class AndroidInstalledComponentPostVerifier(
    context: Context,
) : InstalledComponentPostVerifier {
    private val lookup = AndroidInstalledPackageLookup(context.packageManager)

    override fun verify(
        artifact: ExecutableInstallationArtifact,
        deviceContext: InstallationDeviceContext,
    ): InstalledComponentVerification {
        val metadata = runCatching { lookup.read(artifact.packageName) }.getOrNull()
            ?: return InstalledComponentVerification(
                installed = false,
                enabled = false,
                packageName = null,
                versionCode = null,
                versionName = null,
                reportedSigningCertificateSha256 = emptyList(),
                reportedSignerAccepted = false,
            )
        val reportedSigners = metadata.reportedSigningCertificateSha256
            .map { it.normalizeDigest() }
            .toSet()
        val actualArtifactSigner = setOf(
            artifact.expectedSigningCertificateSha256.normalizeDigest(),
        )
        val compatibilitySigner = deviceContext.isHuaweiHarmonyOneToFour() &&
            reportedSigners == setOf(
                OfficialComponentMatcher.GOOGLE_PRIVILEGED_SIGNER_SHA256,
            )
        return InstalledComponentVerification(
            installed = true,
            enabled = metadata.enabled,
            packageName = metadata.packageName,
            versionCode = metadata.versionCode.toString(),
            versionName = metadata.versionName,
            reportedSigningCertificateSha256 = metadata.reportedSigningCertificateSha256,
            reportedSignerAccepted = reportedSigners == actualArtifactSigner || compatibilitySigner,
        )
    }

    private fun InstallationDeviceContext.isHuaweiHarmonyOneToFour(): Boolean {
        val harmonyMajor = romVersion?.substringBefore('.')
            ?.filter(Char::isDigit)
            ?.toIntOrNull()
        return deviceCategory == DeviceCategory.HUAWEI_HARMONY_ANDROID_COMPAT &&
            manufacturer.contains("huawei", ignoreCase = true) &&
            romFamily == RomFamily.HARMONY_OS &&
            harmonyMajor in 1..4
    }
}

class AndroidFinalEnvironmentVerifier(
    private val componentVerifier: InstalledComponentPostVerifier,
    private val catalogProvider: TrustedCatalogSnapshotProvider,
) : FinalEnvironmentVerifier {
    override fun verify(deviceContext: InstallationDeviceContext): Boolean {
        val artifacts = catalogProvider.currentSnapshot()
            ?.catalog
            ?.releases
            ?.singleOrNull()
            ?.artifacts
            ?: return false
        return artifacts.isNotEmpty() && artifacts.all { artifact ->
            val expected = ExecutableInstallationArtifact(
                componentId = artifact.componentId,
                packageName = artifact.packageName,
                artifactFilename = artifact.artifactFilename ?: return false,
                filePath = "",
                expectedSizeBytes = 0,
                expectedSha256 = artifact.sha256 ?: return false,
                expectedVersionCode = artifact.artifactVersionCode ?: return false,
                expectedVersionName = artifact.artifactVersionName ?: return false,
                expectedSigningCertificateSha256 =
                    artifact.signingCertificateDigest ?: return false,
            )
            val result = componentVerifier.verify(expected, deviceContext)
            result.installed && result.enabled &&
                result.packageName == expected.packageName &&
                result.versionCode == expected.expectedVersionCode &&
                result.versionName == expected.expectedVersionName &&
                result.reportedSigningCertificateSha256.isNotEmpty() &&
                result.reportedSignerAccepted
        }
    }
}

class AndroidInstallationExecutorService(
    private val context: Context,
) {
    private val catalogRepository = RuntimeTrustedCatalogRepository.instance
    private val store = SharedPreferencesInstallationSessionStore(context)
    private val gateway = AndroidPackageInstallerGateway(context)
    private val postVerifier = AndroidInstalledComponentPostVerifier(context)
    private val authorizationSealer = AndroidKeyStoreInstallAuthorizationSealer()
    private val executor = InstallationExecutor(
        sessionStore = store,
        packageInstallerGateway = gateway,
        preparedArtifactRevalidator = AndroidPreparedArtifactRevalidator(context),
        installedComponentVerifier = postVerifier,
        finalEnvironmentVerifier = AndroidFinalEnvironmentVerifier(postVerifier, catalogRepository),
        deviceContextProvider = AndroidInstallationDeviceContextProvider(context, catalogRepository),
        catalogProvider = catalogRepository,
        authorizationPolicy = AuthorizationRevalidationPolicy(authorizationSealer),
    )

    fun begin(request: InstallationExecutionRequest): InstallationExecutionSnapshot =
        executor.begin(request)

    fun installPermissionIntent(): Intent? = gateway.installPermissionIntent()

    fun onInstallPermissionResult(): InstallationExecutionSnapshot? =
        executor.onInstallPermissionResult()

    fun continueExecution(): InstallationExecutionSnapshot? = executor.continueExecution()

    fun restore(): InstallationExecutionSnapshot? = executor.restore()

    fun onPackageInstallerEvent(
        logicalSessionId: String,
        packageInstallerSessionId: Int,
        event: PackageInstallerEvent,
    ): InstallationExecutionSnapshot? = executor.onPackageInstallerEvent(
        logicalSessionId,
        packageInstallerSessionId,
        event,
    )
}

class InstallationResultReceiver : BroadcastReceiver() {
    @SuppressLint("UnsafeIntentLaunch")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val logicalSessionId = intent.getStringExtra(EXTRA_LOGICAL_SESSION_ID) ?: return
        val expectedInstallerSessionId = intent.getIntExtra(
            EXTRA_EXPECTED_INSTALLER_SESSION_ID,
            INVALID_SESSION_ID,
        )
        if (expectedInstallerSessionId == INVALID_SESSION_ID) return
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val confirmationIntent = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            intent.pendingUserActionIntent()
        } else {
            null
        }
        val event = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                PackageInstallerEvent.PENDING_USER_ACTION
            PackageInstaller.STATUS_SUCCESS -> PackageInstallerEvent.SUCCESS
            PackageInstaller.STATUS_FAILURE_ABORTED -> PackageInstallerEvent.USER_CANCELLED
            else -> PackageInstallerEvent.FAILURE
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AndroidInstallationExecutorService(context.applicationContext)
                    .onPackageInstallerEvent(
                        logicalSessionId = logicalSessionId,
                        packageInstallerSessionId = expectedInstallerSessionId,
                        event = event,
                    )
                confirmationIntent?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(this)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.pendingUserActionIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        const val ACTION_INSTALL_STATUS =
            "com.example.globalcompat.action.PACKAGE_INSTALL_STATUS"
        const val EXTRA_LOGICAL_SESSION_ID =
            "com.example.globalcompat.extra.LOGICAL_SESSION_ID"
        const val EXTRA_EXPECTED_INSTALLER_SESSION_ID =
            "com.example.globalcompat.extra.EXPECTED_INSTALLER_SESSION_ID"
        private const val INVALID_SESSION_ID = -1
    }
}

private fun String.normalizeDigest(): String = replace(":", "").lowercase()
