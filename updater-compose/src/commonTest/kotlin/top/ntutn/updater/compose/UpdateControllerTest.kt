@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package top.ntutn.updater.compose

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import top.ntutn.updater.*
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class UpdateControllerTest {
    private val config = UpdaterConfig("https://example.org", "app", UpdateVersion(1, "1"), UpdateTarget(UpdateOs.Linux, UpdateArch.X86_64), "appimage")
    private val release = UpdateRelease("app", config.target, "appimage", UpdateVersion(2, "2"), "https://files.example/app", "Demo", "", "AppImage", null, null, null, 1024, null, "**plain fallback**")
    private class Checker(override val config: UpdaterConfig, var result: UpdateCheckResult) : UpdateChecker {
        var calls = 0
        var wait = 0L
        override suspend fun check(): UpdateCheckResult { calls++; delay(wait); return result }
        override fun close() = Unit
    }
    @Test fun duplicateChecksMergeAndManualPromotesSilent() = runTest {
        val checker = Checker(config, UpdateCheckResult.UpToDate(UpdateVersion(1, "1"))).apply { wait = 100 }
        val controller = UpdateController(checker, BrowserOpener { BrowserOpenResult.Dispatched }, this)
        controller.check(CheckTrigger.Silent); controller.check(); controller.check()
        assertEquals(CheckTrigger.Manual, assertIs<UpdateUiState.Checking>(controller.state.value).trigger)
        advanceUntilIdle()
        assertEquals(1, checker.calls)
        assertIs<UpdateUiState.Feedback>(controller.state.value)
        controller.dismiss(); controller.check(CheckTrigger.Silent); advanceUntilIdle()
        assertIs<UpdateUiState.Idle>(controller.state.value)
        assertIs<UpdateCheckResult.UpToDate>(controller.lastResult.value)
    }
    @Test fun browserFailureRetryAndOneShotDispatch() = runTest {
        val checker = Checker(config, UpdateCheckResult.Available(release))
        var opens = 0
        val controller = UpdateController(checker, BrowserOpener {
            opens++; delay(100)
            if (opens == 1) BrowserOpenResult.Failed(BrowserOpenError.Unavailable) else BrowserOpenResult.Dispatched
        }, this)
        controller.check(); advanceUntilIdle()
        controller.check(); assertEquals(1, checker.calls)
        controller.openDownload(); controller.openDownload(); advanceUntilIdle()
        assertEquals(1, opens)
        assertEquals(BrowserOpenError.Unavailable, assertIs<UpdateUiState.Available>(controller.state.value).browserError)
        controller.openDownload(); advanceUntilIdle()
        assertIs<UpdateUiState.Idle>(controller.state.value)
        assertEquals(UpdateEvent.BrowserDispatched, controller.events.first())
    }
    @Test fun dismissalAndDisposalPreventOldResults() = runTest {
        val checker = Checker(config, UpdateCheckResult.Available(release)).apply { wait = 1000 }
        val controller = UpdateController(checker, BrowserOpener { BrowserOpenResult.Dispatched }, this)
        controller.check(); runCurrent(); controller.dismiss(); advanceUntilIdle()
        assertIs<UpdateUiState.Idle>(controller.state.value)
        assertNull(controller.lastResult.value)
        controller.check(); controller.dispose(); advanceUntilIdle(); controller.check()
        assertIs<UpdateUiState.Idle>(controller.state.value)
    }
    @Test fun cancelledCheckResetsAndCanRunAgain() = runTest {
        val checker = Checker(config, UpdateCheckResult.NoMatchingPackage).apply { wait = 1000 }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = UpdateController(checker, BrowserOpener { BrowserOpenResult.Dispatched }, scope)
        controller.check(); runCurrent(); scope.coroutineContext.cancelChildren(); advanceUntilIdle()
        assertIs<UpdateUiState.Idle>(controller.state.value)
        checker.wait = 0; controller.check(); advanceUntilIdle()
        assertIs<UpdateUiState.Feedback>(controller.state.value)
        scope.cancel()
    }
    @Test fun silentFailureAndCooldownSurviveDismissal() = runTest {
        val failed = UpdateCheckResult.Failed(UpdateError(UpdateErrorKind.ServiceUnavailable, 429, retryAt = Clock.System.now() + 30.seconds))
        val checker = Checker(config, failed)
        val controller = UpdateController(checker, BrowserOpener { BrowserOpenResult.Dispatched }, this)
        controller.check(CheckTrigger.Silent); advanceUntilIdle()
        assertIs<UpdateUiState.Idle>(controller.state.value)
        assertEquals(failed, controller.lastResult.value)
        controller.check(); assertIs<UpdateUiState.Feedback>(controller.state.value)
        controller.dismiss(); controller.check(); advanceUntilIdle()
        assertEquals(1, checker.calls)
    }
    @Test fun cancellingBrowserDoesNotDispatch() = runTest {
        val checker = Checker(config, UpdateCheckResult.Available(release))
        val controller = UpdateController(checker, BrowserOpener { delay(1000); BrowserOpenResult.Dispatched }, this)
        controller.check(); advanceUntilIdle(); controller.openDownload(); runCurrent(); controller.dismiss(); advanceUntilIdle()
        assertIs<UpdateUiState.Idle>(controller.state.value)
    }
    @Test fun cancellationBeforeDispatchResetsState() = runTest {
        val checker = Checker(config, UpdateCheckResult.NoMatchingPackage)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = UpdateController(checker, BrowserOpener { BrowserOpenResult.Dispatched }, scope)
        controller.check(); scope.coroutineContext.cancelChildren(); advanceUntilIdle()
        assertIs<UpdateUiState.Idle>(controller.state.value)
        scope.cancel(); controller.check(); assertIs<UpdateUiState.Idle>(controller.state.value)
    }
    @Test fun packageSizes() {
        assertEquals("0 B", formatPackageSize(0)); assertEquals("1.0 KiB", formatPackageSize(1024))
        assertEquals("64.2 MiB", formatPackageSize(67318579)); assertTrue(formatPackageSize(Long.MAX_VALUE).endsWith("EiB"))
    }
}
