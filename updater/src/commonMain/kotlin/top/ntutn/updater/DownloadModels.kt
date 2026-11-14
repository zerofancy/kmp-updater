package top.ntutn.updater

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Download configuration is independent of the update check's request timeout. */
public data class DownloadOptions(
    public val allowInsecureHttp: Boolean = false,
    public val connectTimeout: Duration = 15.seconds,
    public val readIdleTimeout: Duration = 60.seconds,
    public val maxBytes: Long = 4L * 1024 * 1024 * 1024,
    public val maxRedirects: Int = 5,
    public val progressInterval: Duration = 100.milliseconds,
    public val completedFileRetention: Duration = 7.days,
) {
    init {
        require(connectTimeout.isPositive() && connectTimeout.isFinite())
        require(readIdleTimeout.isPositive() && readIdleTimeout.isFinite())
        require(maxBytes > 0)
        require(maxRedirects in 0..20)
        require(progressInterval.isPositive() && progressInterval.isFinite())
        require(completedFileRetention.isPositive() && completedFileRetention.isFinite())
    }
}

public enum class DownloadPhase { Downloading, Finalizing }

public data class DownloadProgress(
    public val downloadedBytes: Long,
    public val totalBytes: Long?,
    public val phase: DownloadPhase = DownloadPhase.Downloading,
)

/** A completed local file. Its existence must be checked again before handing it to another app. */
public interface DownloadedFile {
    public val localPath: String
    public val fileName: String
    public val sizeBytes: Long
    public suspend fun exists(): Boolean
}

public enum class DownloadErrorKind {
    Network, Timeout, Http, UnsafeUrl, TooManyRedirects, UnsupportedContent, EmptyFile,
    SizeMismatch, TooLarge, InsufficientSpace, FileIo, Busy, Closed,
}

/** Does not include response bodies or URLs, which may contain signed credentials. */
public data class DownloadError(
    public val kind: DownloadErrorKind,
    public val httpStatus: Int? = null,
    public val retryAt: Instant? = null,
)

public sealed interface DownloadResult {
    public data class Completed(public val file: DownloadedFile) : DownloadResult
    public data class Failed(public val error: DownloadError) : DownloadResult
}

public interface UpdateDownloader {
    /** Streams a complete file. Cancellation propagates after temporary-file cleanup. */
    public suspend fun download(
        release: UpdateRelease,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadResult

    /** Deletes only a file created by this downloader. Other handles are rejected. */
    public suspend fun delete(file: DownloadedFile)

    /** Waits for the current download, including cleanup. Call close first to request cancellation. */
    public suspend fun awaitIdle()

    /** Idempotent; cancels this downloader's work. An injected HTTP client remains caller-owned. */
    public fun close()
}
