package top.ntutn.updater

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Clock

class DownloadHttpTest {
    private suspend fun download(
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        size: Long? = null,
        headers: Headers = Headers.Empty,
        status: Int = 200,
        options: DownloadOptions = DownloadOptions(),
        store: MemoryDownloadStore = MemoryDownloadStore(),
        checksum: UpdateChecksum? = null,
        progress: (DownloadProgress) -> Unit = {},
    ): DownloadResult {
        val client = HttpClient(MockEngine { respond(ByteReadChannel(bytes), HttpStatusCode.fromValue(status), headers) }) { followRedirects = false }
        val downloader = createHttpUpdateDownloader(store, options, client)
        return try { downloader.download(downloadRelease(size = size).copy(checksum = checksum), progress) }
        finally { downloader.close(); downloader.awaitIdle(); client.close() }
    }

    @Test fun streamsBoundedChunksAndFinalizesAfterLengthCheck() = runBlocking {
        val store = MemoryDownloadStore()
        val changes = mutableListOf<DownloadProgress>()
        val result = download(ByteArray(3 * 1024 * 1024) { 42 }, 3L * 1024 * 1024, store = store, progress = changes::add)
        assertEquals(3L * 1024 * 1024, assertIs<DownloadResult.Completed>(result).file.sizeBytes)
        assertTrue(store.writes > 1)
        assertTrue(store.maxChunk <= 64 * 1024)
        assertEquals(DownloadProgress(0, 3L * 1024 * 1024), changes.first())
        assertEquals(DownloadPhase.Finalizing, changes.last().phase)
        assertEquals(store.written, changes.last().downloadedBytes)
        assertFalse(store.aborted)
    }

    @Test fun unknownLengthRemainsUnknown() = runBlocking {
        val changes = mutableListOf<DownloadProgress>()
        assertIs<DownloadResult.Completed>(download(progress = changes::add))
        assertTrue(changes.all { it.totalBytes == null })
        val known = mutableListOf<DownloadProgress>()
        assertIs<DownloadResult.Completed>(download(headers = headersOf("Content-Length", "3"), progress = known::add))
        assertTrue(known.all { it.totalBytes == 3L })
    }

    @Test fun verifiesBothDigestsAndDiscardsSameLengthCorruption() = runBlocking {
        val checksums = listOf(
            UpdateChecksum(UpdateChecksumAlgorithm.Sha256, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            UpdateChecksum(UpdateChecksumAlgorithm.Sha512,
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                    "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"),
        )
        for (checksum in checksums) {
            assertIs<DownloadResult.Completed>(download("abc".encodeToByteArray(), 3, checksum = checksum))
            val store = MemoryDownloadStore()
            val failed = assertIs<DownloadResult.Failed>(download("abd".encodeToByteArray(), 3, store = store, checksum = checksum))
            assertEquals(DownloadErrorKind.ChecksumMismatch, failed.error.kind)
            assertEquals(3L, store.written)
            assertTrue(store.aborted)
            assertFalse(store.completed)
        }
    }

    @Test fun lengthErrorsAndLimitsNeverPublishFiles() = runBlocking {
        val cases = listOf(
            Triple(byteArrayOf(1, 2, 3), 2L, DownloadErrorKind.SizeMismatch),
            Triple(byteArrayOf(1, 2, 3), 4L, DownloadErrorKind.SizeMismatch),
            Triple(byteArrayOf(), null, DownloadErrorKind.EmptyFile),
            Triple(byteArrayOf(), 0L, DownloadErrorKind.EmptyFile),
            Triple(byteArrayOf(1), -1L, DownloadErrorKind.SizeMismatch),
        )
        for ((bytes, size, expected) in cases) {
            val store = MemoryDownloadStore()
            assertEquals(expected, assertIs<DownloadResult.Failed>(download(bytes, size, store = store)).error.kind)
            assertFalse(store.completed)
            assertTrue(store.begins == 0 || store.aborted)
        }
        assertEquals(DownloadErrorKind.TooLarge, assertIs<DownloadResult.Failed>(download(options = DownloadOptions(maxBytes = 2))).error.kind)
        assertEquals(DownloadErrorKind.TooLarge, assertIs<DownloadResult.Failed>(download(size = 3, options = DownloadOptions(maxBytes = 2))).error.kind)
        assertEquals(DownloadErrorKind.SizeMismatch, assertIs<DownloadResult.Failed>(download(size = 3, headers = headersOf("Content-Length", "4"))).error.kind)
        assertEquals(DownloadErrorKind.SizeMismatch, assertIs<DownloadResult.Failed>(download(headers = headersOf("Content-Length", "broken"))).error.kind)
    }

    @Test fun unsupportedPagesAndEncodingRejectedButGenericBinaryAccepted() = runBlocking {
        for (type in listOf("text/html", "application/xhtml+xml", "application/json", "application/problem+json")) {
            assertEquals(DownloadErrorKind.UnsupportedContent, assertIs<DownloadResult.Failed>(download(headers = headersOf("Content-Type", type))).error.kind)
        }
        for (prefix in listOf("<!DOCTYPE html>", "\uFEFF \n<html><body>Error", "<head><title>Download")) {
            assertEquals(DownloadErrorKind.UnsupportedContent, assertIs<DownloadResult.Failed>(download(prefix.encodeToByteArray())).error.kind)
        }
        assertEquals(DownloadErrorKind.UnsupportedContent, assertIs<DownloadResult.Failed>(download(headers = headersOf("Content-Encoding", "gzip"))).error.kind)
        assertIs<DownloadResult.Completed>(download(headers = headersOf("Content-Type", "application/octet-stream")))
        Unit
    }

    @Test fun statusFailuresRetainStatusWithoutReadingPageOrRetrying() = runBlocking {
        for (status in listOf(204, 206, 401, 403, 404, 500, 503)) {
            val error = assertIs<DownloadResult.Failed>(download(status = status)).error
            assertEquals(DownloadErrorKind.Http, error.kind)
            assertEquals(status, error.httpStatus)
        }
        val before = Clock.System.now()
        val limited = assertIs<DownloadResult.Failed>(download(status = 429, headers = headersOf("Retry-After", "60"))).error
        assertTrue(limited.retryAt!! > before)
    }

    @Test fun relativeRedirectsUseRfcResolutionAndKeepQuery() {
        assertEquals("https://files.example/a/pkg", resolveDownloadRedirect("https://files.example/a/b/app", "../pkg", false))
        assertEquals("https://files.example/a/app?token=2", resolveDownloadRedirect("https://files.example/a/app?token=1", "?token=2", false))
        assertEquals("https://cdn.example/pkg", resolveDownloadRedirect("https://files.example/a/app", "//cdn.example/pkg", false))
        for (url in listOf("http://files.example/pkg", "file:///tmp/x", "https://u:p@files.example/a", "https://files.example/\n")) {
            assertEquals(DownloadErrorKind.UnsafeUrl, assertFailsWith<DownloadFailure> { resolveDownloadRedirect("https://files.example/app", url, true) }.error.kind)
        }
    }

    @Test fun followsSafeCrossDomainRedirectAndBoundsLoops() = runBlocking {
        val calls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            calls += request.url.toString()
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HttpHeaders.Cookie])
            assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
            when (request.url.host) {
                "files.example" -> respond("", HttpStatusCode.Found, headersOf("Location", "https://cdn.example/package"))
                else -> respond(byteArrayOf(1, 2, 3))
            }
        }) { followRedirects = false }
        val downloader = createHttpUpdateDownloader(MemoryDownloadStore(), DownloadOptions(), client)
        assertIs<DownloadResult.Completed>(downloader.download(downloadRelease()))
        assertEquals(listOf("https://files.example/app", "https://cdn.example/package"), calls)
        downloader.close(); client.close()
        val loopClient = HttpClient(MockEngine { respond("", HttpStatusCode.TemporaryRedirect, headersOf("Location", "/app")) }) { followRedirects = false }
        val loop = createHttpUpdateDownloader(MemoryDownloadStore(), DownloadOptions(maxRedirects = 2), loopClient)
        assertEquals(DownloadErrorKind.TooManyRedirects, assertIs<DownloadResult.Failed>(loop.download(downloadRelease())).error.kind)
        loop.close(); loopClient.close()
    }
}
