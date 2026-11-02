package top.ntutn.updater

import io.ktor.http.Url

/** Validation shared by configuration, protocol parsing and browser adapters. */
public fun isAllowedWebUrl(value: String, allowInsecureHttp: Boolean = false, originOnly: Boolean = false): Boolean {
    if (!Regex("https?://.*", RegexOption.IGNORE_CASE).matches(value) || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return false
    return runCatching {
        val url = Url(value)
        val scheme = url.protocol.name.lowercase()
        url.host.isNotBlank() && url.user == null && url.password == null &&
            (scheme == "https" || (allowInsecureHttp && scheme == "http")) &&
            (!originOnly || (url.encodedPath in setOf("", "/") && '?' !in value && '#' !in value))
    }.getOrDefault(false)
}

public enum class BrowserOpenError { UnsafeUrl, Unavailable }
public sealed interface BrowserOpenResult {
    public data object Dispatched : BrowserOpenResult
    public data class Failed(public val reason: BrowserOpenError) : BrowserOpenResult
}
public fun interface BrowserOpener {
    /** Dispatched means the system accepted the URL, not that download or installation succeeded. */
    public suspend fun open(url: String): BrowserOpenResult
}
