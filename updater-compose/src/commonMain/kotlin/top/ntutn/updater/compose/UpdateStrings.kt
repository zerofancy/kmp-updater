package top.ntutn.updater.compose

public data class UpdateStrings(
    public val foundUpdate: String = "发现新版本",
    public val checking: String = "正在检查更新…",
    public val current: String = "当前",
    public val latest: String = "最新",
    public val browserHint: String = "将在浏览器中打开下载地址",
    public val later: String = "暂不更新",
    public val download: String = "前往下载",
    public val opening: String = "正在打开…",
    public val retryOpen: String = "重试打开",
    public val browserFailed: String = "无法打开浏览器，可重试或选择复制下方地址",
    public val upToDate: String = "当前已是最新版本",
    public val noUpdate: String = "暂无可用更新",
    public val noPackage: String = "暂无适用于当前安装版本的更新包",
    public val failed: String = "检查更新失败，请稍后重试",
    public val retry: String = "重试",
    public val close: String = "关闭",
    public val cooldown: String = "请稍后重试",
) {
    public companion object {
        public val Chinese: UpdateStrings = UpdateStrings()
        public val English: UpdateStrings = UpdateStrings(
            "Update available", "Checking for updates…", "Current", "Latest",
            "The download link will open in your browser", "Later", "Go to download", "Opening…",
            "Retry opening", "Could not open the browser. Retry or select and copy the link below",
            "You are up to date", "No update available", "No package for this installation",
            "Could not check for updates. Please try again later", "Retry", "Close", "Please retry later",
        )
    }
}
