package top.ntutn.updater

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

public enum class UpdateOs { Android, Linux, Windows, MacOS }
public enum class UpdateArch { X86_64, ARM64 }
public data class UpdateTarget(public val os: UpdateOs, public val arch: UpdateArch)

public sealed interface TargetDetection {
    public data class Supported(public val target: UpdateTarget) : TargetDetection
    public data object UnsupportedPlatform : TargetDetection
}

/** Detects the running process, not the original installer. Override the target for emulated installations. */
public expect fun detectUpdateTarget(): TargetDetection

public fun desktopUpdateTarget(osName: String, archName: String): TargetDetection {
    val os = when {
        osName.startsWith("Windows", ignoreCase = true) -> UpdateOs.Windows
        osName.equals("Linux", ignoreCase = true) -> UpdateOs.Linux
        osName.equals("Mac OS X", ignoreCase = true) || osName.equals("macOS", ignoreCase = true) -> UpdateOs.MacOS
        else -> return TargetDetection.UnsupportedPlatform
    }
    val arch = when (archName.lowercase()) {
        "amd64", "x86_64" -> UpdateArch.X86_64
        "aarch64", "arm64" -> UpdateArch.ARM64
        else -> return TargetDetection.UnsupportedPlatform
    }
    return TargetDetection.Supported(UpdateTarget(os, arch))
}

public data class UpdaterConfig(
    public val baseUrl: String,
    public val software: String,
    public val currentVersion: UpdateVersion,
    public val target: UpdateTarget,
    public val packageType: String,
    public val requestTimeout: Duration = 10.seconds,
    public val allowInsecureHttp: Boolean = false,
) {
    init {
        require(isAllowedWebUrl(baseUrl, allowInsecureHttp, originOnly = true)) { "baseUrl must be an HTTP(S) origin; HTTPS is required by default" }
        require(Regex("[a-z0-9][a-z0-9._-]{0,99}").matches(software)) { "Invalid software" }
        require(Regex("[a-z0-9][a-z0-9-]{0,31}").matches(packageType)) { "Invalid packageType" }
        require(requestTimeout.isPositive() && requestTimeout.isFinite()) { "requestTimeout must be positive and finite" }
    }
}

public data class UpdateRelease(
    public val software: String,
    public val target: UpdateTarget,
    public val packageType: String,
    public val version: UpdateVersion,
    public val downloadUrl: String,
    public val softwareDisplayName: String,
    public val distributionTitle: String,
    public val packageName: String,
    public val distributionId: String?,
    public val packageId: String?,
    public val publishedAt: Instant?,
    public val packageSize: Long?,
    public val descriptionPlainText: String?,
    public val descriptionMarkdown: String?,
) {
    public val description: String? get() = descriptionPlainText?.takeIf { it.isNotBlank() }
        ?: descriptionMarkdown?.takeIf { it.isNotBlank() }
}

public enum class UpdateErrorKind { ServiceConfiguration, AmbiguousLatest, ServiceUnavailable, Http, Network, Timeout, InvalidResponse, UnsafeDownloadUrl, Closed }
public data class UpdateError(
    public val kind: UpdateErrorKind,
    public val httpStatus: Int? = null,
    public val serviceCode: String? = null,
    public val retryAt: Instant? = null,
)
public sealed interface UpdateCheckResult {
    public data class Available(public val release: UpdateRelease) : UpdateCheckResult
    public data class UpToDate(public val latest: UpdateVersion) : UpdateCheckResult
    public data object NoMatchingPackage : UpdateCheckResult
    public data class Failed(public val error: UpdateError) : UpdateCheckResult
}
public interface UpdateChecker {
    public val config: UpdaterConfig
    public suspend fun check(): UpdateCheckResult
    /** Cancels this checker's in-flight calls. Injected clients remain owned by the caller. */
    public fun close()
}
