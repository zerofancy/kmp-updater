package top.ntutn.updater

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class DownloadLifetimeTest {
    @Test fun closeAwaitIdleWaitsForCleanupAndDoesNotCloseInjectedClient() = runBlocking {
        val store = MemoryDownloadStore().apply { abortGate = CompletableDeferred() }
        val client = HttpClient(MockEngine { respond(ByteChannel()) }) { followRedirects = false }
        val downloader = createHttpUpdateDownloader(store, DownloadOptions(), client)
        val call = async { downloader.download(downloadRelease()) }
        store.started.await()
        downloader.close(); downloader.close()
        store.abortStarted.await()
        val idle = async { downloader.awaitIdle() }
        delay(20)
        assertFalse(idle.isCompleted, "awaitIdle returned before cancellation cleanup")
        assertFalse(store.aborted)
        store.abortGate!!.complete(Unit)
        idle.await()
        assertTrue(store.aborted)
        assertFailsWith<CancellationException> { call.await() }
        assertTrue(client.coroutineContext[Job]!!.isActive)
        assertEquals(DownloadErrorKind.Closed, assertIs<DownloadResult.Failed>(downloader.download(downloadRelease())).error.kind)
        client.close()
    }

    @Test fun callerCancellationReleasesBusySlotAfterCleanup() = runBlocking {
        val store = MemoryDownloadStore()
        var requests = 0
        val client = HttpClient(MockEngine { requests++; if (requests == 1) respond(ByteChannel()) else respond(byteArrayOf(1, 2, 3)) }) { followRedirects = false }
        val downloader = createHttpUpdateDownloader(store, DownloadOptions(), client)
        val call = async { downloader.download(downloadRelease()) }
        store.started.await()
        assertEquals(DownloadErrorKind.Busy, assertIs<DownloadResult.Failed>(downloader.download(downloadRelease())).error.kind)
        call.cancelAndJoin()
        downloader.awaitIdle()
        assertTrue(store.aborted)
        assertIs<DownloadResult.Completed>(downloader.download(downloadRelease()))
        assertEquals(2, requests)
        downloader.close(); client.close()
    }

    @Test fun stalledReadTimesOutAndCleansFile() = runBlocking {
        val store = MemoryDownloadStore()
        val client = HttpClient(MockEngine { respond(ByteChannel()) }) { followRedirects = false }
        val downloader = createHttpUpdateDownloader(store, DownloadOptions(readIdleTimeout = 30.milliseconds), client)
        assertEquals(DownloadErrorKind.Timeout, assertIs<DownloadResult.Failed>(downloader.download(downloadRelease())).error.kind)
        assertTrue(store.aborted)
        downloader.close(); client.close()
    }

    @Test fun unsafeUrlsNeverIssueRequests() = runBlocking {
        val client = HttpClient(MockEngine { error("Must not request unsafe URL") })
        val downloader = createHttpUpdateDownloader(MemoryDownloadStore(), DownloadOptions(), client)
        for (url in listOf("http://files.example/app", "file:///tmp/x", "https://u:p@files.example/app")) {
            assertEquals(DownloadErrorKind.UnsafeUrl, assertIs<DownloadResult.Failed>(downloader.download(downloadRelease(url))).error.kind)
        }
        downloader.close(); client.close()
    }
}
