package top.ntutn.updater

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal expect fun updateHttpEngine(): HttpClientEngine

/** The provided client must not install authentication, cookies, retries, or automatic redirects for this public endpoint. */
public fun createUpdateChecker(config: UpdaterConfig, client: HttpClient? = null): UpdateChecker =
    HttpUpdateChecker(config, client ?: HttpClient(updateHttpEngine()) { followRedirects = false }, client == null)

private class HttpUpdateChecker(
    override val config: UpdaterConfig,
    private val client: HttpClient,
    private val ownsClient: Boolean,
) : UpdateChecker {
    private val lifetime = Job()

    override suspend fun check(): UpdateCheckResult {
        if (!lifetime.isActive) return UpdateCheckResult.Failed(UpdateError(UpdateErrorKind.Closed))
        val callJob = Job(currentCoroutineContext()[Job])
        val handle = lifetime.invokeOnCompletion { callJob.cancel() }
        try {
            return withContext(callJob) {
                withTimeoutOrNull(config.requestTimeout) { query() }
                    ?: UpdateCheckResult.Failed(UpdateError(UpdateErrorKind.Timeout))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            return UpdateCheckResult.Failed(UpdateError(UpdateErrorKind.Timeout))
        } catch (_: InvalidUpdateResponse) {
            return UpdateCheckResult.Failed(UpdateError(UpdateErrorKind.InvalidResponse))
        } catch (_: UnsafeUpdateUrl) {
            return UpdateCheckResult.Failed(UpdateError(UpdateErrorKind.UnsafeDownloadUrl))
        } catch (_: Exception) {
            return UpdateCheckResult.Failed(UpdateError(UpdateErrorKind.Network))
        } finally {
            handle.dispose()
            callJob.complete()
        }
    }

    private suspend fun query(): UpdateCheckResult = client.prepareGet(config.baseUrl.trimEnd('/') + "/api/v1/updates") {
        expectSuccess = false
        header(HttpHeaders.Accept, ContentType.Application.Json)
        parameter("software", config.software)
        parameter("os", config.target.os.name)
        parameter("arch", config.target.arch.name)
        parameter("packageType", config.packageType)
    }.execute { response ->
        val status = response.status.value
        // Bound the decoded stream as well as Content-Length (which may be absent or compressed).
        val channel = response.bodyAsChannel()
        val bytes = ByteArray(256 * 1024 + 1)
        var count = 0
        while (count < bytes.size) {
            val read = channel.readAvailable(bytes, count, bytes.size - count)
            if (read == -1) break
            count += read
        }
        if (count == bytes.size) throw InvalidUpdateResponse()
        val root = if (response.contentType()?.withoutParameters() == ContentType.Application.Json) {
            runCatching { Json.parseToJsonElement(bytes.decodeToString(0, count, throwOnInvalidSequence = true)) as? JsonObject }.getOrNull()
        } else null
        if (status != 200) {
            val error = root?.get("error") as? JsonObject
            val code = (error?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (status == 404 && code == "NO_MATCHING_PACKAGE") return@execute UpdateCheckResult.NoMatchingPackage
            val kind = when {
                status == 400 || (status == 404 && code == "SOFTWARE_NOT_FOUND") -> UpdateErrorKind.ServiceConfiguration
                status == 409 && code == "AMBIGUOUS_LATEST" -> UpdateErrorKind.AmbiguousLatest
                status in setOf(429, 500, 503) -> UpdateErrorKind.ServiceUnavailable
                else -> UpdateErrorKind.Http
            }
            return@execute UpdateCheckResult.Failed(UpdateError(kind, status, code,
                if (status == 429) retryAt(response.headers[HttpHeaders.RetryAfter]) else null))
        }
        val release = parseRelease(root ?: throw InvalidUpdateResponse(), config)
        if (release.version.isNewerThan(config.currentVersion)) UpdateCheckResult.Available(release)
        else UpdateCheckResult.UpToDate(release.version)
    }

    override fun close() {
        lifetime.cancel()
        if (ownsClient) client.close()
    }
}

private fun retryAt(value: String?): Instant {
    val now = Clock.System.now()
    value?.toLongOrNull()?.takeIf { it >= 0 && it <= 31536000 }?.let { return now + it.seconds }
    return runCatching { value?.let { Instant.fromEpochMilliseconds(it.fromHttpToGmtDate().timestamp) } }
        .getOrNull()?.takeIf { it > now } ?: (now + 30.seconds)
}
