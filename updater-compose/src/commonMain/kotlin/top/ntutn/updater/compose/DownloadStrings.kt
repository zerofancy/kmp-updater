package top.ntutn.updater.compose

import top.ntutn.updater.DownloadErrorKind

public data class DownloadStrings(
    public val download: String = "下载更新",
    public val downloading: String = "正在下载更新",
    public val cancel: String = "取消下载",
    public val cancelling: String = "正在取消…",
    public val finalizing: String = "正在保存更新…",
    public val completed: String = "下载完成",
    public val failed: String = "下载未完成",
    public val retry: String = "重新下载",
    public val browserDownload: String = "浏览器下载",
    public val done: String = "完成",
    public val saveHint: String = "下载文件将保存在应用更新目录",
    public val downloaded: String = "已下载",
    public val unknownSize: String = "服务器未提供总大小",
    public val networkFailed: String = "网络连接中断，请检查网络后重新下载",
    public val timeout: String = "下载连接超时，请稍后重新下载",
    public val unavailable: String = "下载链接暂时不可用，可稍后重新检查更新或使用浏览器下载",
    public val unsupported: String = "该地址不支持直接下载，请使用浏览器下载",
    public val invalidFile: String = "收到的更新文件不完整，请重新下载",
    public val tooLarge: String = "更新文件超出应用允许的大小，请使用浏览器下载",
    public val storageFailed: String = "无法保存更新文件，请检查存储空间和目录权限",
    public val noSpace: String = "存储空间不足，请清理空间后重新下载",
    public val actionRunning: String = "正在打开…",
    public val fileMissing: String = "下载文件已不存在，请重新检查更新并下载",
    public val actionFailed: String = "无法打开下载文件，请重试或使用显示的保存位置",
) {
    public companion object {
        public val Chinese: DownloadStrings = DownloadStrings()
        public val English: DownloadStrings = DownloadStrings(
            download = "Download update", downloading = "Downloading update", cancel = "Cancel download",
            cancelling = "Cancelling…", finalizing = "Saving update…", completed = "Download complete",
            failed = "Download incomplete", retry = "Download again", browserDownload = "Download in browser",
            done = "Done", saveHint = "The file will be saved in the application's update directory",
            downloaded = "Downloaded", unknownSize = "The server did not provide a total size",
            networkFailed = "The connection was interrupted. Check your network and download again",
            timeout = "The download connection timed out. Please try again later",
            unavailable = "The download link is unavailable. Check for updates later or use your browser",
            unsupported = "This address does not support direct downloads. Use your browser",
            invalidFile = "The update file is incomplete. Please download again",
            tooLarge = "The update exceeds this application's download limit. Use your browser",
            storageFailed = "Could not save the update. Check free space and directory permissions",
            noSpace = "Not enough storage space. Free some space and download again",
            actionRunning = "Opening…", fileMissing = "The file no longer exists. Check for updates and download again",
            actionFailed = "Could not open the downloaded file. Retry or use the displayed location",
        )
    }
}

internal fun DownloadStrings.error(kind: DownloadErrorKind): String = when (kind) {
    DownloadErrorKind.Network -> networkFailed
    DownloadErrorKind.Timeout -> timeout
    DownloadErrorKind.UnsupportedContent -> unsupported
    DownloadErrorKind.EmptyFile, DownloadErrorKind.SizeMismatch -> invalidFile
    DownloadErrorKind.TooLarge -> tooLarge
    DownloadErrorKind.InsufficientSpace -> noSpace
    DownloadErrorKind.FileIo -> storageFailed
    else -> unavailable
}
