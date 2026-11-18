package top.ntutn.updater.compose

import top.ntutn.updater.AndroidInstallError
import top.ntutn.updater.AndroidInstallSettingsResult

public data class AndroidInstallStrings(
    public val install: String = "安装更新",
    public val preparing: String = "正在准备安装…",
    public val dispatched: String = "已将安装请求交给 Android，请按系统提示继续。",
    public val permissionRequired: String = "允许此应用安装未知应用，返回后再次发起安装。",
    public val openPermissionSettings: String = "打开安装权限设置",
    public val settingsUnavailable: String = "无法打开安装来源设置，请在系统设置中检查权限。",
    public val unsupportedFile: String = "此文件不是可以安装的 APK 更新包。",
    public val fileUnavailable: String = "下载文件已丢失或发生变化，请重新检查更新并下载。",
    public val invalidApk: String = "Android 无法读取此 APK，请重新下载。",
    public val packageMismatch: String = "此 APK 的应用 ID 与当前应用不同。",
    public val notNewer: String = "此 APK 的版本未高于已安装版本。",
    public val permissionNotDeclared: String = "当前应用未声明请求安装 APK 的权限。",
    public val activityUnavailable: String = "当前界面已不可用，请回到应用后重试。",
    public val providerUnavailable: String = "无法向 Android 提供更新文件，请重试。",
    public val installerUnavailable: String = "此设备没有可用的 APK 安装程序。",
    public val launchDenied: String = "Android 拒绝打开安装界面。",
    public val busy: String = "正在处理此更新包，请稍后重试。",
) {
    public companion object {
        public val Chinese: AndroidInstallStrings = AndroidInstallStrings()

        public val English: AndroidInstallStrings = AndroidInstallStrings(
            install = "Install update",
            preparing = "Preparing installation…",
            dispatched = "Install request sent to Android. Follow the system prompts.",
            permissionRequired = "Allow this app to install unknown apps, then return and retry installation.",
            openPermissionSettings = "Open install permission settings",
            settingsUnavailable = "Could not open install permission settings. Check your system settings.",
            unsupportedFile = "This file is not an installable APK update.",
            fileUnavailable = "The downloaded file is missing or has changed. Check for updates and download again.",
            invalidApk = "Android could not read this APK. Download it again.",
            packageMismatch = "This APK has a different application ID.",
            notNewer = "This APK is not newer than the installed version.",
            permissionNotDeclared = "This app does not declare permission to request APK installation.",
            activityUnavailable = "This screen is unavailable. Return to the app and try again.",
            providerUnavailable = "Could not share the update file with Android. Try again.",
            installerUnavailable = "No APK installer is available on this device.",
            launchDenied = "Android refused to launch the installer.",
            busy = "This update is being prepared. Try again in a moment.",
        )
    }
}

internal fun AndroidInstallStrings.error(reason: AndroidInstallError): String = when (reason) {
    AndroidInstallError.UnsupportedFile -> unsupportedFile
    AndroidInstallError.FileUnavailable -> fileUnavailable
    AndroidInstallError.InvalidApk -> invalidApk
    AndroidInstallError.PackageMismatch -> packageMismatch
    AndroidInstallError.NotNewer -> notNewer
    AndroidInstallError.PermissionNotDeclared -> permissionNotDeclared
    AndroidInstallError.ActivityUnavailable -> activityUnavailable
    AndroidInstallError.ProviderUnavailable -> providerUnavailable
    AndroidInstallError.InstallerUnavailable -> installerUnavailable
    AndroidInstallError.LaunchDenied -> launchDenied
    AndroidInstallError.Busy -> busy
}

internal fun AndroidInstallStrings.error(result: AndroidInstallSettingsResult): String? = when (result) {
    AndroidInstallSettingsResult.Opened -> null
    AndroidInstallSettingsResult.PermissionNotDeclared -> permissionNotDeclared
    AndroidInstallSettingsResult.ActivityUnavailable -> activityUnavailable
    AndroidInstallSettingsResult.Unsupported, AndroidInstallSettingsResult.SettingsUnavailable -> settingsUnavailable
}
