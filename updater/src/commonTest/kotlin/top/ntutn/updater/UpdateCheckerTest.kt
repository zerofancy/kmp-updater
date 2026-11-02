package top.ntutn.updater

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Clock

class UpdateCheckerTest {
    private val config = UpdaterConfig("https://updates.example", "demo", UpdateVersion(9, "9"), UpdateTarget(UpdateOs.Linux, UpdateArch.X86_64), "appimage")
    private val sequence = """"software":"demo","os":"Linux","arch":"X86_64","packageType":"appimage""""
    private fun body(code: String = "\"10\"", extra: String = "", url: String = "https://files.example/app?a=1&b=2"): String =
        """{"query":{$sequence},"items":[{$sequence,"latest":{"packageType":"appimage","versionCode":$code,"versionName":"v10","downloadUrl":"$url"$extra}}]}"""

    private suspend fun query(body: String, status: Int = 200, contentType: String = "application/json", headers: Headers = Headers.Empty): UpdateCheckResult {
        val engine = MockEngine { request ->
            assertEquals("/api/v1/updates", request.url.encodedPath)
            assertEquals(setOf("software", "os", "arch", "packageType"), request.url.parameters.names())
            assertEquals(listOf("demo"), request.url.parameters.getAll("software"))
            assertEquals("Linux", request.url.parameters["os"])
            assertEquals("X86_64", request.url.parameters["arch"])
            assertEquals("appimage", request.url.parameters["packageType"])
            assertEquals("application/json", request.headers[HttpHeaders.Accept])
            respond(body, HttpStatusCode.fromValue(status), Headers.build {
                append(HttpHeaders.ContentType, contentType)
                appendAll(headers)
            })
        }
        val client = HttpClient(engine) { followRedirects = false }
        val checker = createUpdateChecker(config, client)
        return try { checker.check() } finally { checker.close(); client.close() }
    }

    @Test fun numericComparisonAndLongPrecision() = runBlocking {
        assertIs<UpdateCheckResult.Available>(query(body()))
        assertIs<UpdateCheckResult.UpToDate>(query(body("\"9\"")))
        assertIs<UpdateCheckResult.UpToDate>(query(body("\"8\"")))
        assertEquals(Long.MAX_VALUE, assertIs<UpdateCheckResult.Available>(query(body("\"${Long.MAX_VALUE}\""))).release.version.code)
    }
    @Test fun descriptionsAndOptionalMetadata() = runBlocking {
        val release = assertIs<UpdateCheckResult.Available>(query(body(extra = ",\"packageSize\":\"0\",\"descriptionPlainText\":\"Plain <literal>\",\"descriptionMarkdown\":\"**Markdown**\",\"futureField\":true"))).release
        assertEquals(0, release.packageSize)
        assertEquals("Plain <literal>", release.description)
        assertEquals("demo", release.softwareDisplayName)
        assertNull(release.publishedAt)
        val fallback = assertIs<UpdateCheckResult.Available>(query(body(extra = ",\"descriptionPlainText\":null,\"descriptionMarkdown\":\"**Markdown**\""))).release
        assertEquals("**Markdown**", fallback.description)
        assertNull(assertIs<UpdateCheckResult.Available>(query(body())).release.packageSize)
    }
    @Test fun malformedSuccessCannotBecomeNoUpdate() = runBlocking {
        val malformed = listOf(
            body("10"), body("\"0\""), body("\"-1\""), body("\"9223372036854775808\""), body("\"1.2\""),
            body().replace("\"versionName\":\"v10\",", ""),
            body().replace("\"downloadUrl\"", "\"missingUrl\""),
            body().replace("\"query\"", "\"missingQuery\""),
            body().replace("\"arch\":\"X86_64\"", "\"arch\":\"ARM64\""),
            body().replace("\"latest\":{\"packageType\":\"appimage\"", "\"latest\":{\"packageType\":\"deb\""),
            body(extra = ",\"packageSize\":\"-1\""), body(extra = ",\"packageSize\":42"), body(extra = ",\"packageSize\":\"\""),
            """{"query":{$sequence},"items":[]}""", "[]", "{}", "<html>Error</html>", "x".repeat(256 * 1024 + 1),
        )
        malformed.forEach { assertEquals(UpdateErrorKind.InvalidResponse, assertIs<UpdateCheckResult.Failed>(query(it)).error.kind, it.take(120)) }
        assertEquals(UpdateErrorKind.InvalidResponse, assertIs<UpdateCheckResult.Failed>(query(body(), contentType = "text/html")).error.kind)
    }
    @Test fun unsafeUrlsRejected() = runBlocking {
        listOf("http://files.example/a", "javascript:alert(1)", "file:///tmp/app", "https://user:pass@files.example/a").forEach {
            assertEquals(UpdateErrorKind.UnsafeDownloadUrl, assertIs<UpdateCheckResult.Failed>(query(body(url = it))).error.kind)
        }
    }
    @Test fun serviceErrorsRetainMeaning() = runBlocking {
        suspend fun error(status: Int, code: String) = query("""{"error":{"code":"$code"}}""", status)
        assertEquals(UpdateCheckResult.NoMatchingPackage, error(404, "NO_MATCHING_PACKAGE"))
        listOf(400 to "INVALID_ARGUMENT", 404 to "SOFTWARE_NOT_FOUND").forEach { (status, code) ->
            val failure = assertIs<UpdateCheckResult.Failed>(error(status, code)).error
            assertEquals(UpdateErrorKind.ServiceConfiguration, failure.kind)
            assertEquals(status, failure.httpStatus); assertEquals(code, failure.serviceCode)
        }
        assertEquals(UpdateErrorKind.AmbiguousLatest, assertIs<UpdateCheckResult.Failed>(error(409, "AMBIGUOUS_LATEST")).error.kind)
        listOf(500, 503, 429).forEach { assertEquals(UpdateErrorKind.ServiceUnavailable, assertIs<UpdateCheckResult.Failed>(error(it, "FUTURE_ERROR")).error.kind) }
        assertEquals(UpdateErrorKind.Http, assertIs<UpdateCheckResult.Failed>(error(418, "FUTURE_ERROR")).error.kind)
        assertEquals(UpdateErrorKind.Http, assertIs<UpdateCheckResult.Failed>(query("", 302, headers = headersOf("Location", "http://other.example"))).error.kind)
    }
    @Test fun rateLimitRetainsRetryTime() = runBlocking {
        val before = Clock.System.now()
        val error = assertIs<UpdateCheckResult.Failed>(query("{}", 429, headers = headersOf("Retry-After", "60"))).error
        assertTrue(error.retryAt!! >= before + kotlin.time.Duration.parse("60s"))
    }
    @Test fun timeoutNetworkCancellationAndOwnership() = runBlocking {
        val client = HttpClient(MockEngine { delay(1000); respond(body()) })
        val checker = createUpdateChecker(config.copy(requestTimeout = 50.milliseconds), client)
        assertEquals(UpdateErrorKind.Timeout, assertIs<UpdateCheckResult.Failed>(checker.check()).error.kind)
        val call = async { checker.check() }
        delay(10); checker.close(); checker.close()
        assertFailsWith<CancellationException> { call.await() }
        assertEquals(UpdateErrorKind.Closed, assertIs<UpdateCheckResult.Failed>(checker.check()).error.kind)
        assertTrue(client.coroutineContext[Job]!!.isActive)
        client.close()
        val broken = HttpClient(MockEngine { throw javaLikeNetworkFailure() })
        val brokenChecker = createUpdateChecker(config, broken)
        assertEquals(UpdateErrorKind.Network, assertIs<UpdateCheckResult.Failed>(brokenChecker.check()).error.kind)
        brokenChecker.close(); broken.close()
    }
    private fun javaLikeNetworkFailure() = IllegalStateException("offline")
    @Test fun callerCancellationPropagates() = runBlocking {
        val client = HttpClient(MockEngine { awaitCancellation() })
        val checker = createUpdateChecker(config, client)
        val call = async { checker.check() }
        delay(10); call.cancel()
        assertFailsWith<CancellationException> { call.await() }
        checker.close(); client.close()
    }
    @Test fun configurationAndPlatformMappings() {
        listOf("Linux", "Windows 11", "Mac OS X", "macOS").forEach { os ->
            listOf("amd64", "x86_64", "aarch64", "arm64").forEach { arch -> assertIs<TargetDetection.Supported>(desktopUpdateTarget(os, arch)) }
        }
        assertIs<TargetDetection.UnsupportedPlatform>(desktopUpdateTarget("Darwin", "x86_64"))
        assertIs<TargetDetection.UnsupportedPlatform>(desktopUpdateTarget("Linux", "armv7"))
        listOf("https://host/path", "https://host?x=1", "https://host#", "http://host", "https://u:p@host", "file:///tmp").forEach {
            assertFailsWith<IllegalArgumentException> { config.copy(baseUrl = it) }
        }
        assertFalse(isAllowedWebUrl("https://host/\nfile"))
        assertTrue(isAllowedWebUrl("http://localhost:18082/a?x=1&y=2", true))
        assertFailsWith<IllegalArgumentException> { config.copy(packageType = "AppImage") }
    }
}
