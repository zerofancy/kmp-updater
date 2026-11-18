package top.ntutn.updater

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.MainThread
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.ntutn.updater.AndroidInstallResult.Failed
import java.io.IOException

public sealed interface AndroidInstallResult {
    /** Android accepted the activity launch. The user and platform still decide whether to install. */
    public data object Dispatched : AndroidInstallResult
    public data object PermissionRequired : AndroidInstallResult
    public data class Failed(public val reason: AndroidInstallError) : AndroidInstallResult
}

public enum class AndroidInstallError {
    UnsupportedFile, FileUnavailable, InvalidApk, PackageMismatch, NotNewer, PermissionNotDeclared,
    ActivityUnavailable, ProviderUnavailable, InstallerUnavailable, LaunchDenied, Busy,
}

public enum class AndroidInstallSettingsResult {
    Opened, Unsupported, PermissionNotDeclared, ActivityUnavailable, SettingsUnavailable,
}

/**
 * Hands a completed Android SDK download to the device's APK handler. Call from a user action in
 * the foreground and cancel the preparation when that UI becomes inactive. Does not retain the Activity.
 */
public suspend fun requestAndroidUpdateInstall(activity: Activity, file: DownloadedFile): AndroidInstallResult {
    if (withContext(Dispatchers.Main.immediate) { activity.isUnavailable() }) {
        return Failed(AndroidInstallError.ActivityUnavailable)
    }
    return try {
        JvmDownloadFileStore.withCompletedApk(androidUpdateDirectory(activity), file) { handoff ->
            prepareAndDispatch(activity, handoff)
        }
    } catch (failure: ApkHandoffFailure) {
        Failed(when (failure.reason) {
            ApkHandoffError.Unsupported -> AndroidInstallError.UnsupportedFile
            ApkHandoffError.Unavailable -> AndroidInstallError.FileUnavailable
            ApkHandoffError.Busy -> AndroidInstallError.Busy
        })
    } catch (_: IOException) {
        Failed(AndroidInstallError.FileUnavailable)
    } catch (_: SecurityException) {
        Failed(AndroidInstallError.LaunchDenied)
    } catch (_: PackageManager.NameNotFoundException) {
        Failed(AndroidInstallError.ActivityUnavailable)
    }
}

/** Opens the source-specific settings UI. On return, request installation again to recheck access. */
@MainThread
public fun openAndroidInstallPermissionSettings(activity: Activity): AndroidInstallSettingsResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return AndroidInstallSettingsResult.Unsupported
    if (activity.isUnavailable()) return AndroidInstallSettingsResult.ActivityUnavailable
    return try {
        if (!activity.installedPackage().declaresInstallPermission()) {
            return AndroidInstallSettingsResult.PermissionNotDeclared
        }
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", activity.packageName, null))
        activity.startActivity(intent)
        AndroidInstallSettingsResult.Opened
    } catch (_: ActivityNotFoundException) {
        AndroidInstallSettingsResult.SettingsUnavailable
    } catch (_: SecurityException) {
        AndroidInstallSettingsResult.SettingsUnavailable
    } catch (_: PackageManager.NameNotFoundException) {
        AndroidInstallSettingsResult.SettingsUnavailable
    }
}

@Suppress("DEPRECATION") // The int flag overload is available on every supported Android version.
private suspend fun prepareAndDispatch(activity: Activity, handoff: ApkHandoff): AndroidInstallResult {
    val manager = activity.packageManager
    val candidate = manager.getPackageArchiveInfo(handoff.apk.path, 0)
        ?: return Failed(AndroidInstallError.InvalidApk)
    if (candidate.packageName != activity.packageName) return Failed(AndroidInstallError.PackageMismatch)
    val installed = activity.installedPackage()
    if (candidate.versionCodeForInstall() <= installed.versionCodeForInstall()) return Failed(AndroidInstallError.NotNewer)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        if (!installed.declaresInstallPermission()) return Failed(AndroidInstallError.PermissionNotDeclared)
        if (!manager.canRequestPackageInstalls()) return AndroidInstallResult.PermissionRequired
    }

    val uri = try {
        FileProvider.getUriForFile(activity, "${activity.packageName}.kmp-updater.install", handoff.apk)
    } catch (_: IllegalArgumentException) {
        return Failed(AndroidInstallError.ProviderUnavailable)
    } catch (_: SecurityException) {
        return Failed(AndroidInstallError.ProviderUnavailable)
    }
    currentCoroutineContext().ensureActive()
    val previousProtection = try {
        handoff.protect()
    } catch (_: IOException) {
        return Failed(AndroidInstallError.ProviderUnavailable)
    } catch (_: SecurityException) {
        return Failed(AndroidInstallError.ProviderUnavailable)
    }
    val result = withContext(Dispatchers.Main.immediate) { activity.dispatchInstall(uri) }
    if (result is Failed) runCatching { handoff.setProtectionUntil(previousProtection) }
    return result
}

@MainThread
private fun Activity.dispatchInstall(uri: Uri): AndroidInstallResult {
    if (isUnavailable()) return Failed(AndroidInstallError.ActivityUnavailable)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        startActivity(intent)
        AndroidInstallResult.Dispatched
    } catch (_: ActivityNotFoundException) {
        Failed(AndroidInstallError.InstallerUnavailable)
    } catch (_: SecurityException) {
        Failed(AndroidInstallError.LaunchDenied)
    }
}

private fun Activity.isUnavailable(): Boolean = isFinishing || isDestroyed

@Suppress("DEPRECATION")
private fun Activity.installedPackage(): PackageInfo =
    packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)

private fun PackageInfo.declaresInstallPermission(): Boolean =
    requestedPermissions?.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES) == true

@Suppress("DEPRECATION")
private fun PackageInfo.versionCodeForInstall(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

/** FileProvider subclass registered by the Android library manifest. */
public class UpdateApkFileProvider : FileProvider()
