package com.example.globalcompat.data

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

interface ComponentScanner {
    fun scan(): List<SystemComponent>
}

class AndroidComponentScanner(
    private val packageManager: PackageManager,
) : ComponentScanner {
    override fun scan(): List<SystemComponent> = COMPONENTS.map(::scanComponent)

    private fun scanComponent(component: ComponentDescriptor): SystemComponent {
        val packageInfo = try {
            getPackageInfo(component.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return component.toResult(ComponentPresence.NOT_INSTALLED)
        } catch (_: RuntimeException) {
            return component.toResult(ComponentPresence.CHECK_FAILED)
        }

        return component.toResult(
            presence = ComponentPresence.PRESENT,
            packageInfo = packageInfo,
        )
    }

    private fun getPackageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }

    private data class ComponentDescriptor(
        val id: ComponentId,
        val displayName: String,
        val packageName: String,
    ) {
        fun toResult(
            presence: ComponentPresence,
            packageInfo: PackageInfo? = null,
        ) = SystemComponent(
            id = id,
            displayName = displayName,
            packageName = packageName,
            presence = presence,
            enabled = packageInfo?.applicationInfo?.enabled,
            versionName = packageInfo?.versionName,
            versionCode = packageInfo?.let(PackageInfoCompat::getLongVersionCode),
        )
    }

    private companion object {
        val COMPONENTS = listOf(
            ComponentDescriptor(
                id = ComponentId.GOOGLE_PLAY_SERVICES,
                displayName = "Google Play 服务",
                packageName = "com.google.android.gms",
            ),
            ComponentDescriptor(
                id = ComponentId.GOOGLE_PLAY_STORE,
                displayName = "Google Play 商店",
                packageName = "com.android.vending",
            ),
            ComponentDescriptor(
                id = ComponentId.HMS_CORE,
                displayName = "HMS Core",
                packageName = "com.huawei.hwid",
            ),
        )
    }
}
