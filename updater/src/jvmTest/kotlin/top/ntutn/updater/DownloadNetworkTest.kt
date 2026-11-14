package top.ntutn.updater

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/** Uses the production OkHttp engine against real loopback HTTP and the real filesystem. */
class DownloadNetworkTest {
    private suspend fun fixture(test: suspend (String, File) -> Unit) {
        val root = Files.createTempDirectory("updater-network-").toFile()
        val executor = Executors.newCachedThreadPool { task -> Thread(task, "download-test-http").apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = executor }
        server.createContext("/binary") { exchange ->
            exchange.sendResponseHeaders(200, 0) // HTTP chunked, no Content-Length.
            exchange.responseBody.use { body ->
                val chunk = ByteArray(8192) { (it % 251).toByte() }
                repeat(256) { body.write(chunk) }
            }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "binary?token=loopback")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/slow") { exchange ->
            runCatching {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { body ->
                    repeat(1000) {
                        body.write(ByteArray(4096) { 42 }); body.flush(); Thread.sleep(5)
                    }
                }
            }
            exchange.close()
        }
        server.createContext("/stall") { exchange ->
            runCatching {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.flush()
                Thread.sleep(2000)
            }
            exchange.close()
        }
        server.start()
        try { test("http://127.0.0.1:${server.address.port}", root) }
        finally { server.stop(0); executor.shutdownNow(); root.deleteRecursively() }
    }

    @Test fun followsRealRedirectAndWritesChunkedFile() = runBlocking {
        fixture { base, root ->
            val downloader = createDesktopUpdateDownloader(root, DownloadOptions(allowInsecureHttp = true))
            try {
                val progress = mutableListOf<DownloadProgress>()
                val file = assertIs<DownloadResult.Completed>(downloader.download(downloadRelease("$base/redirect"), progress::add)).file
                assertEquals(2L * 1024 * 1024, file.sizeBytes)
                assertTrue(file.exists())
                assertTrue(progress.all { it.totalBytes == null })
                File(file.localPath).inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    repeat(256) {
                        var offset = 0
                        while (offset < buffer.size) offset += input.read(buffer, offset, buffer.size - offset)
                        assertContentEquals(ByteArray(8192) { (it % 251).toByte() }, buffer)
                    }
                    assertEquals(-1, input.read())
                }
                downloader.delete(file)
            } finally { downloader.close(); downloader.awaitIdle() }
        }
    }

    @Test fun cancelsRealStreamingRequestAndRemovesPartialFile() = runBlocking {
        fixture { base, root ->
            val downloader = createDesktopUpdateDownloader(root, DownloadOptions(allowInsecureHttp = true, progressInterval = 10.milliseconds))
            val received = CompletableDeferred<Unit>()
            try {
                coroutineScope {
                    val call = async { downloader.download(downloadRelease("$base/slow")) { if (it.downloadedBytes > 0) received.complete(Unit) } }
                    withTimeout(5000) { received.await() }
                    assertTrue(root.walkTopDown().any { it.name == "payload.part" })
                    call.cancelAndJoin()
                }
                downloader.awaitIdle()
                assertFalse(root.walkTopDown().any { it.name.startsWith("task-") })
            } finally { downloader.close(); downloader.awaitIdle() }
        }
    }

    @Test fun productionSocketTimeoutIsReportedAsTimeout() = runBlocking {
        fixture { base, root ->
            val downloader = createDesktopUpdateDownloader(root, DownloadOptions(allowInsecureHttp = true, readIdleTimeout = 100.milliseconds))
            try {
                val failure = withTimeout(5000) { assertIs<DownloadResult.Failed>(downloader.download(downloadRelease("$base/stall"))) }
                assertEquals(DownloadErrorKind.Timeout, failure.error.kind)
                assertFalse(root.walkTopDown().any { it.name == "payload.part" })
            } finally { downloader.close(); downloader.awaitIdle() }
        }
    }
}
