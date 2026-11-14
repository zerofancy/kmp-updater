@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package top.ntutn.updater.compose

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import top.ntutn.updater.*
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class DownloadControllerTest {
    private val config = UpdaterConfig("https://example.org", "app", UpdateVersion(1, "1"), UpdateTarget(UpdateOs.Linux, UpdateArch.X86_64), "appimage")
    private val release = UpdateRelease("app", config.target, "appimage", UpdateVersion(2, "2"), "https://files.example/app", "Demo", "", "AppImage", null, null, null, 1024, null, null)

    private class Checker(override val config: UpdaterConfig, val release: UpdateRelease) : UpdateChecker {
        var calls = 0
        override suspend fun check(): UpdateCheckResult { calls++; return UpdateCheckResult.Available(release) }
        override fun close() = Unit
    }

    private class File : DownloadedFile {
        override val localPath = "/updates/app.AppImage"
        override val fileName = "app.AppImage"
        override val sizeBytes = 1024L
        var present = true
        override suspend fun exists(): Boolean = present
    }

    private class Downloader : UpdateDownloader {
        val file = File()
        var calls = 0
        var deletes = 0
        var closes = 0
        var cleanups = 0
        var cleanupDelay = 100L
        var behavior: suspend ((DownloadProgress) -> Unit) -> DownloadResult = { DownloadResult.Completed(file) }
        override suspend fun download(release: UpdateRelease, onProgress: (DownloadProgress) -> Unit): DownloadResult {
            calls++
            try { return behavior(onProgress) }
            finally { withContext(NonCancellable) { delay(cleanupDelay); cleanups++ } }
        }
        override suspend fun delete(file: DownloadedFile) { deletes++ }
        override suspend fun awaitIdle() = Unit
        override fun close() { closes++ }
    }

    @Test fun downloadDeduplicatesBlocksBrowserAndKeepsCompletedFile() = runTest {
        val checker = Checker(config, release)
        val source = Downloader().apply {
            behavior = { progress ->
                progress(DownloadProgress(512, 1024))
                delay(500)
                progress(DownloadProgress(1024, 1024, DownloadPhase.Finalizing))
                delay(500)
                DownloadResult.Completed(file)
            }
        }
        var browserCalls = 0
        val controller = UpdateController(checker, BrowserOpener { browserCalls++; BrowserOpenResult.Dispatched }, source, this)
        controller.check(); advanceUntilIdle()
        controller.startDownload(); controller.startDownload(); runCurrent()
        controller.openDownload(); controller.check()
        assertEquals(1, source.calls)
        assertEquals(1, checker.calls)
        assertEquals(0, browserCalls)
        assertEquals(512L, assertIs<UpdateDownloadState.Downloading>(controller.downloadState.value).progress.downloadedBytes)
        advanceTimeBy(500); runCurrent()
        assertIs<UpdateDownloadState.Finalizing>(controller.downloadState.value)
        advanceUntilIdle()
        assertSame(source.file, assertIs<UpdateDownloadState.Completed>(controller.downloadState.value).file)
        controller.dismiss()
        assertEquals(0, source.deletes)
        assertEquals(0, source.closes)
    }

    @Test fun cancellationWaitsForCleanupBeforeRetry() = runTest {
        val source = Downloader().apply { behavior = { delay(10_000); DownloadResult.Completed(file) } }
        val controller = UpdateController(Checker(config, release), BrowserOpener { BrowserOpenResult.Dispatched }, source, this)
        controller.check(); advanceUntilIdle()
        controller.startDownload(); runCurrent(); controller.cancelDownload(); runCurrent()
        assertIs<UpdateDownloadState.Cancelling>(controller.downloadState.value)
        controller.startDownload()
        assertEquals(1, source.calls)
        advanceUntilIdle()
        assertIs<UpdateDownloadState.Idle>(controller.downloadState.value)
        assertIs<UpdateUiState.Available>(controller.state.value)
        assertEquals(1, source.cleanups)
        source.behavior = { DownloadResult.Completed(source.file) }
        controller.startDownload(); advanceUntilIdle()
        assertIs<UpdateDownloadState.Completed>(controller.downloadState.value)
        assertEquals(2, source.calls)
    }

    @Test fun lateSuccessAfterCancellationIsDeletedAndCannotReplaceState() = runTest {
        val source = Downloader().apply {
            behavior = { progress ->
                try { delay(10_000) } catch (_: CancellationException) { }
                progress(DownloadProgress(1024, 1024))
                DownloadResult.Completed(file)
            }
        }
        val controller = UpdateController(Checker(config, release), BrowserOpener { BrowserOpenResult.Dispatched }, source, this)
        controller.check(); advanceUntilIdle()
        controller.startDownload(); runCurrent(); controller.dismiss(); advanceUntilIdle()
        assertIs<UpdateUiState.Idle>(controller.state.value)
        assertIs<UpdateDownloadState.Idle>(controller.downloadState.value)
        assertEquals(1, source.deletes)
    }

    @Test fun progressQueuedAtCompletionCannotRegressCompleted() = runTest {
        val source = Downloader().apply {
            cleanupDelay = 0
            behavior = { progress ->
                progress(DownloadProgress(100, null))
                DownloadResult.Completed(file)
            }
        }
        val controller = UpdateController(Checker(config, release), BrowserOpener { BrowserOpenResult.Dispatched }, source, this)
        controller.check(); advanceUntilIdle(); controller.startDownload(); advanceUntilIdle()
        assertIs<UpdateDownloadState.Completed>(controller.downloadState.value)
        assertEquals(0, source.deletes)
    }

    @Test fun failedDownloadCanRetryWithoutRechecking() = runTest {
        val checker = Checker(config, release)
        val source = Downloader().apply { behavior = { DownloadResult.Failed(DownloadError(DownloadErrorKind.FileIo)) } }
        val controller = UpdateController(checker, BrowserOpener { BrowserOpenResult.Dispatched }, source, this)
        controller.check(); advanceUntilIdle(); controller.startDownload(); advanceUntilIdle()
        assertEquals(DownloadErrorKind.FileIo, assertIs<UpdateDownloadState.Failed>(controller.downloadState.value).error.kind)
        source.behavior = { DownloadResult.Completed(source.file) }
        controller.retryDownload(); advanceUntilIdle()
        assertIs<UpdateDownloadState.Completed>(controller.downloadState.value)
        assertEquals(2, source.calls)
        assertEquals(1, checker.calls)
    }

    @Test fun downloadCooldownSurvivesDismissalAndRecheck() = runTest {
        val error = DownloadError(DownloadErrorKind.Http, 429, Clock.System.now() + 30.seconds)
        val source = Downloader().apply { behavior = { DownloadResult.Failed(error) } }
        val controller = UpdateController(Checker(config, release), BrowserOpener { BrowserOpenResult.Dispatched }, source, this)
        controller.check(); advanceUntilIdle(); controller.startDownload(); advanceUntilIdle()
        controller.dismiss(); controller.check(); advanceUntilIdle(); controller.startDownload(); advanceUntilIdle()
        assertEquals(1, source.calls)
        assertEquals(error, assertIs<UpdateDownloadState.Failed>(controller.downloadState.value).error)
    }

    @Test fun missingFileNeverInvokesCompletionAction() = runTest {
        val source = Downloader()
        val controller = UpdateController(Checker(config, release), BrowserOpener { BrowserOpenResult.Dispatched }, source, this)
        controller.check(); advanceUntilIdle(); controller.startDownload(); advanceUntilIdle()
        source.file.present = false
        var calls = 0
        controller.performCompletedAction(CompletedDownloadAction("Open") { calls++; true }); advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(CompletedActionError.FileMissing, assertIs<UpdateDownloadState.Completed>(controller.downloadState.value).actionError)
    }

    @Test fun completionActionDeduplicatesAndFailuresRemainRetryable() = runTest {
        val source = Downloader()
        val controller = UpdateController(Checker(config, release), BrowserOpener { BrowserOpenResult.Dispatched }, source, this)
        controller.check(); advanceUntilIdle(); controller.startDownload(); advanceUntilIdle()
        var calls = 0
        val action = CompletedDownloadAction("Open") { calls++; delay(500); calls > 1 }
        controller.performCompletedAction(action); controller.performCompletedAction(action); advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(CompletedActionError.Failed, assertIs<UpdateDownloadState.Completed>(controller.downloadState.value).actionError)
        controller.performCompletedAction(action); advanceUntilIdle()
        assertEquals(2, calls)
        assertNull(assertIs<UpdateDownloadState.Completed>(controller.downloadState.value).actionError)
    }

    @Test fun cancelledBeforeActionDispatchResetsRunningFlag() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val source = Downloader()
        val controller = UpdateController(Checker(config, release), BrowserOpener { BrowserOpenResult.Dispatched }, source, owner)
        controller.check(); advanceUntilIdle(); controller.startDownload(); advanceUntilIdle()
        var calls = 0
        val action = CompletedDownloadAction("Open") { calls++; true }
        controller.performCompletedAction(action)
        owner.coroutineContext.cancelChildren(); advanceUntilIdle()
        assertFalse(assertIs<UpdateDownloadState.Completed>(controller.downloadState.value).actionRunning)
        assertEquals(0, calls)
        controller.performCompletedAction(action); advanceUntilIdle()
        assertEquals(1, calls)
        owner.cancel()
    }

    @Test fun cancelledBeforeDownloadDispatchResetsAndCanRunAgain() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val source = Downloader()
        val controller = UpdateController(Checker(config, release), BrowserOpener { BrowserOpenResult.Dispatched }, source, owner)
        controller.check(); advanceUntilIdle(); controller.startDownload()
        owner.coroutineContext.cancelChildren(); advanceUntilIdle()
        assertIs<UpdateDownloadState.Idle>(controller.downloadState.value)
        assertEquals(0, source.calls)
        controller.startDownload(); advanceUntilIdle()
        assertIs<UpdateDownloadState.Completed>(controller.downloadState.value)
        owner.cancel()
    }

    @Test fun disposalCancelsTasksButDoesNotCloseCallerDownloader() = runTest {
        val source = Downloader().apply { behavior = { delay(10_000); DownloadResult.Completed(file) } }
        val controller = UpdateController(Checker(config, release), BrowserOpener { BrowserOpenResult.Dispatched }, source, this)
        controller.check(); advanceUntilIdle(); controller.startDownload(); runCurrent()
        controller.dispose(); advanceUntilIdle(); controller.startDownload()
        assertEquals(1, source.calls)
        assertEquals(1, source.cleanups)
        assertEquals(0, source.closes)
        assertIs<UpdateUiState.Idle>(controller.state.value)
    }

    @Test fun immediatelyCancelledDownloaderDoesNotLeaveProgressStuck() = runTest {
        val owner = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val source = Downloader().apply {
            cleanupDelay = 0
            behavior = { throw CancellationException("Cancelled before streaming") }
        }
        val controller = UpdateController(Checker(config, release), BrowserOpener { BrowserOpenResult.Dispatched }, source, owner)
        controller.check(); controller.startDownload(); advanceUntilIdle()
        assertIs<UpdateDownloadState.Idle>(controller.downloadState.value)
        assertEquals(1, source.calls)
        owner.cancel()
    }

    @Test fun cancellationBeforeBrowserDispatchAllowsAnotherAttempt() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var browserCalls = 0
        val controller = UpdateController(Checker(config, release), BrowserOpener { browserCalls++; BrowserOpenResult.Dispatched }, owner)
        controller.check(); advanceUntilIdle(); controller.openDownload()
        owner.coroutineContext.cancelChildren(); advanceUntilIdle()
        assertFalse(assertIs<UpdateUiState.Available>(controller.state.value).opening)
        assertEquals(0, browserCalls)
        controller.openDownload(); advanceUntilIdle()
        assertEquals(1, browserCalls)
        owner.cancel()
    }

    @Test fun oldControllerRemainsBrowserOnly() = runTest {
        var browserCalls = 0
        val controller = UpdateController(Checker(config, release), BrowserOpener { browserCalls++; BrowserOpenResult.Dispatched }, this)
        controller.check(); advanceUntilIdle(); controller.startDownload(); advanceUntilIdle()
        assertFalse(controller.downloadEnabled)
        assertIs<UpdateDownloadState.Idle>(controller.downloadState.value)
        controller.openDownload(); advanceUntilIdle()
        assertEquals(1, browserCalls)
        assertIs<UpdateUiState.Idle>(controller.state.value)
    }
}
