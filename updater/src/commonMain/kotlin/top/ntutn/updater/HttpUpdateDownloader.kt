package top.ntutn.updater

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/** Injected clients must disable redirects, retries, authentication, and cookies for downloads. */
internal fun createHttpUpdateDownloader(
    store: DownloadFileStore,
    options: DownloadOptions,
    client: HttpClient? = null,
): UpdateDownloader = HttpUpdateDownloader(store, options, client ?: HttpClient(updateHttpEngine()) {
    followRedirects = false
    install(HttpTimeout)
}, client == null)

private class HttpUpdateDownloader(
    private val store: DownloadFileStore,
    private val options: DownloadOptions,
    private val client: HttpClient,
    private val ownsClient: Boolean,
) : UpdateDownloader {
    private val lifetime = Job()
    private class Call(val job: CompletableJob, val finished: CompletableDeferred<Unit> = CompletableDeferred())
    private val active = MutableStateFlow<Call?>(null)

    override suspend fun download(release: UpdateRelease, onProgress: (DownloadProgress) -> Unit): DownloadResult {
        if (!lifetime.isActive) return DownloadResult.Failed(DownloadError(DownloadErrorKind.Closed))
        val job = Job(currentCoroutineContext()[Job])
        val call = Call(job)
        if (!active.compareAndSet(null, call)) {
            job.complete()
            return DownloadResult.Failed(DownloadError(DownloadErrorKind.Busy))
        }
        val cancellation = lifetime.invokeOnCompletion { job.cancel() }
        var session: DownloadFileSession? = null
        var delivered = false
        try {
            val result = withContext(job + downloadIoDispatcher()) {
                if (!isAllowedWebUrl(release.downloadUrl, options.allowInsecureHttp)) failDownload(DownloadErrorKind.UnsafeUrl)
                if (release.packageSize?.let { it < 0 } == true) failDownload(DownloadErrorKind.SizeMismatch)
                if (release.packageSize?.let { it > options.maxBytes } == true) failDownload(DownloadErrorKind.TooLarge)
                fetch(release, onProgress) { created -> session = created }
            }
            delivered = true
            return DownloadResult.Completed(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DownloadFailure) {
            return DownloadResult.Failed(failure.error)
        } catch (_: HttpRequestTimeoutException) {
            return DownloadResult.Failed(DownloadError(DownloadErrorKind.Timeout))
        } catch (_: ConnectTimeoutException) {
            return DownloadResult.Failed(DownloadError(DownloadErrorKind.Timeout))
        } catch (_: SocketTimeoutException) {
            return DownloadResult.Failed(DownloadError(DownloadErrorKind.Timeout))
        } catch (error: Exception) {
            return DownloadResult.Failed(DownloadError(downloadNetworkErrorKind(error)))
        } finally {
            try {
                if (!delivered) withContext(NonCancellable + downloadIoDispatcher()) { session?.abort() }
            } finally {
                cancellation.dispose()
                job.complete()
                active.compareAndSet(call, null)
                call.finished.complete(Unit)
            }
        }
    }

    private suspend fun fetch(
        release: UpdateRelease,
        progress: (DownloadProgress) -> Unit,
        opened: (DownloadFileSession) -> Unit,
    ): DownloadedFile {
        var url = release.downloadUrl
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            var next: String? = null
            val file = client.prepareGet(url) {
                expectSuccess = false
                header(HttpHeaders.AcceptEncoding, "identity")
                timeout {
                    connectTimeoutMillis = options.connectTimeout.inWholeMilliseconds.coerceAtLeast(1)
                    socketTimeoutMillis = options.readIdleTimeout.inWholeMilliseconds.coerceAtLeast(1)
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
            }.execute { response ->
                val status = response.status.value
                // An injected client must not bypass our validation with its redirect plugin.
                if (response.call.request.url != Url(url)) failDownload(DownloadErrorKind.UnsafeUrl)
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (redirects++ >= options.maxRedirects) failDownload(DownloadErrorKind.TooManyRedirects, status)
                    val location = response.headers[HttpHeaders.Location] ?: failDownload(DownloadErrorKind.Http, status)
                    next = resolveDownloadRedirect(url, location, options.allowInsecureHttp)
                    return@execute null
                }
                if (status != 200) throw DownloadFailure(DownloadError(DownloadErrorKind.Http, status,
                    if (status == 429) downloadRetryAt(response.headers[HttpHeaders.RetryAfter]) else null))
                receive(response, release, progress, opened)
            }
            if (file != null) return file
            url = next ?: failDownload(DownloadErrorKind.Http)
        }
    }

    private suspend fun receive(
        response: HttpResponse,
        release: UpdateRelease,
        progress: (DownloadProgress) -> Unit,
        opened: (DownloadFileSession) -> Unit,
    ): DownloadedFile {
        val encoding = response.headers[HttpHeaders.ContentEncoding]
        if (encoding != null && !encoding.equals("identity", true)) failDownload(DownloadErrorKind.UnsupportedContent)
        val type = response.contentType()?.withoutParameters()?.toString()?.lowercase()
        if (type == "text/html" || type == "application/xhtml+xml" || type == "application/json" || type?.endsWith("+json") == true) {
            failDownload(DownloadErrorKind.UnsupportedContent)
        }
        val rawLength = response.headers[HttpHeaders.ContentLength]
        val contentLength = rawLength?.toLongOrNull()?.takeIf { it >= 0 }
        if (rawLength != null && contentLength == null) failDownload(DownloadErrorKind.SizeMismatch)
        if (contentLength != null && release.packageSize != null && contentLength != release.packageSize) failDownload(DownloadErrorKind.SizeMismatch)
        val total = release.packageSize ?: contentLength
        if (total != null && total > options.maxBytes) failDownload(DownloadErrorKind.TooLarge)
        val session = store.begin(release, total)
        opened(session)
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(64 * 1024)
        val prefix = ByteArray(512)
        var prefixSize = 0
        var count = 0L
        var lastProgress = TimeSource.Monotonic.markNow()
        progress(DownloadProgress(0, total))
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = withTimeoutOrNull(options.readIdleTimeout) { channel.readAvailable(buffer, 0, buffer.size) }
                ?: failDownload(DownloadErrorKind.Timeout)
            if (read == -1) break
            if (read == 0) continue
            if (read.toLong() > options.maxBytes - count) failDownload(DownloadErrorKind.TooLarge)
            count += read
            if (total != null && count > total) failDownload(DownloadErrorKind.SizeMismatch)
            val prefixRead = minOf(read, prefix.size - prefixSize)
            buffer.copyInto(prefix, prefixSize, 0, prefixRead)
            prefixSize += prefixRead
            if (looksLikeHtml(prefix, prefixSize)) failDownload(DownloadErrorKind.UnsupportedContent)
            session.write(buffer, read)
            if (lastProgress.elapsedNow() >= options.progressInterval) {
                progress(DownloadProgress(count, total))
                lastProgress = TimeSource.Monotonic.markNow()
            }
        }
        if (count == 0L) failDownload(DownloadErrorKind.EmptyFile)
        if (total != null && count != total) failDownload(DownloadErrorKind.SizeMismatch)
        progress(DownloadProgress(count, total, DownloadPhase.Finalizing))
        currentCoroutineContext().ensureActive()
        return session.finish(count)
    }

    override suspend fun delete(file: DownloadedFile): Unit = store.delete(file)
    override suspend fun awaitIdle() { active.value?.finished?.await() }
    override fun close() {
        lifetime.cancel()
        if (ownsClient) client.close()
    }
}

/** Ktor byte channels wrap non-copyable transport failures in a channel-closed exception. */
internal fun downloadNetworkErrorKind(error: Throwable): DownloadErrorKind {
    var current: Throwable? = error
    repeat(16) {
        val cause = current ?: return DownloadErrorKind.Network
        if (cause is HttpRequestTimeoutException || cause is ConnectTimeoutException || cause is SocketTimeoutException) {
            return DownloadErrorKind.Timeout
        }
        current = cause.cause
    }
    return DownloadErrorKind.Network
}

internal fun resolveDownloadRedirect(base: String, location: String, insecure: Boolean): String {
    if (location.isBlank() || location.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) failDownload(DownloadErrorKind.UnsafeUrl)
    val next = runCatching {
        URLBuilder(base).apply {
            if (!location.startsWith('#')) {
                parameters.clear()
                trailingQuery = false
            }
            encodedFragment = ""
            takeFrom(location)
            val normalized = mutableListOf<String>()
            val segments = encodedPath.split('/')
            segments.forEachIndexed { index, segment ->
                when (segment) {
                    "." -> if (index == segments.lastIndex) normalized.add("")
                    ".." -> {
                        if (normalized.size > 1) normalized.removeAt(normalized.lastIndex)
                        if (index == segments.lastIndex) normalized.add("")
                    }
                    else -> normalized.add(segment)
                }
            }
            encodedPath = normalized.joinToString("/")
        }.buildString()
    }
        .getOrElse { failDownload(DownloadErrorKind.UnsafeUrl) }
    if (!isAllowedWebUrl(next, insecure) || (Url(base).protocol == URLProtocol.HTTPS && Url(next).protocol != URLProtocol.HTTPS)) {
        failDownload(DownloadErrorKind.UnsafeUrl)
    }
    return next
}

private fun looksLikeHtml(bytes: ByteArray, size: Int): Boolean {
    val prefix = bytes.decodeToString(0, size).trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase()
    return prefix.startsWith("<!doctype html") || prefix.startsWith("<html") || prefix.startsWith("<head") || prefix.startsWith("<body")
}

private fun downloadRetryAt(value: String?): Instant {
    val now = Clock.System.now()
    value?.toLongOrNull()?.takeIf { it in 0..31536000 }?.let { return now + it.seconds }
    return runCatching { value?.let { Instant.fromEpochMilliseconds(it.fromHttpToGmtDate().timestamp) } }
        .getOrNull()?.takeIf { it > now } ?: (now + 30.seconds)
}
