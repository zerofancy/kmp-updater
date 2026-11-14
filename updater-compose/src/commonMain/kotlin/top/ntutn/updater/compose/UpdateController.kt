package top.ntutn.updater.compose

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import top.ntutn.updater.*
import kotlin.time.Clock

public enum class CheckTrigger { Manual, Silent }
public sealed interface UpdateUiState {
    public data object Idle : UpdateUiState
    public data class Checking(public val trigger: CheckTrigger) : UpdateUiState
    public data class Available(
        public val release: UpdateRelease,
        public val opening: Boolean = false,
        public val browserError: BrowserOpenError? = null,
    ) : UpdateUiState
    public data class Feedback(public val result: UpdateCheckResult) : UpdateUiState
}
public sealed interface UpdateEvent {
    public data object BrowserDispatched : UpdateEvent
}

/** Call on the UI thread. Does not own or close the injected checker or downloader. */
public class UpdateController private constructor(
    private val checker: UpdateChecker,
    private val browserOpener: BrowserOpener,
    private val scope: CoroutineScope,
    private val downloader: UpdateDownloader?,
) {
    public constructor(checker: UpdateChecker, browserOpener: BrowserOpener, scope: CoroutineScope) :
        this(checker, browserOpener, scope, null)

    public constructor(checker: UpdateChecker, browserOpener: BrowserOpener, downloader: UpdateDownloader, scope: CoroutineScope) :
        this(checker, browserOpener, scope, downloader)

    public val config: UpdaterConfig get() = checker.config
    public val downloadEnabled: Boolean get() = downloader != null
    private val mutableState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    public val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()
    private val mutableResult = MutableStateFlow<UpdateCheckResult?>(null)
    public val lastResult: StateFlow<UpdateCheckResult?> = mutableResult.asStateFlow()
    private val mutableDownloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    public val downloadState: StateFlow<UpdateDownloadState> = mutableDownloadState.asStateFlow()
    private val eventChannel = Channel<UpdateEvent>(Channel.BUFFERED)
    public val events: Flow<UpdateEvent> = eventChannel.receiveAsFlow()
    private var task: Job? = null
    private var downloadTask: Job? = null
    private var generation = 0L
    private var disposed = false
    // Retained across dismissal so closing a 429 message cannot bypass Retry-After.
    private var cooldown: UpdateCheckResult.Failed? = null
    private var downloadCooldown: DownloadError? = null
    private val downloadBusy: Boolean get() = downloadTask?.isCompleted == false

    public fun check(trigger: CheckTrigger = CheckTrigger.Manual) {
        if (disposed || !scope.isActive || downloadBusy) return
        val current = state.value
        if (current is UpdateUiState.Available) return
        if (current is UpdateUiState.Checking) {
            if (trigger == CheckTrigger.Manual) mutableState.value = UpdateUiState.Checking(trigger)
            return
        }
        val limited = cooldown?.takeIf { it.error.retryAt?.let { at -> at > Clock.System.now() } == true }
        if (limited != null) {
            if (trigger == CheckTrigger.Manual) mutableState.value = UpdateUiState.Feedback(limited)
            return
        }
        val token = ++generation
        mutableState.value = UpdateUiState.Checking(trigger)
        task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = checker.check()
                if (token != generation) return@launch
                mutableResult.value = result
                cooldown = (result as? UpdateCheckResult.Failed)?.takeIf { it.error.retryAt != null }
                val manual = (state.value as? UpdateUiState.Checking)?.trigger == CheckTrigger.Manual
                mutableState.value = when {
                    result is UpdateCheckResult.Available -> UpdateUiState.Available(result.release)
                    manual -> UpdateUiState.Feedback(result)
                    else -> UpdateUiState.Idle
                }
            } finally {
                if (token == generation && state.value is UpdateUiState.Checking) mutableState.value = UpdateUiState.Idle
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (token == generation && state.value is UpdateUiState.Checking) mutableState.value = UpdateUiState.Idle
            }
            job.start()
        }
    }

    public fun openDownload() {
        if (disposed || !scope.isActive || downloadBusy) return
        if ((downloadState.value as? UpdateDownloadState.Completed)?.actionRunning == true) return
        val available = state.value as? UpdateUiState.Available ?: return
        if (available.opening) return
        val token = ++generation
        mutableState.value = available.copy(opening = true, browserError = null)
        task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = if (!isAllowedWebUrl(available.release.downloadUrl, config.allowInsecureHttp)) {
                    BrowserOpenResult.Failed(BrowserOpenError.UnsafeUrl)
                } else try { browserOpener.open(available.release.downloadUrl) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { BrowserOpenResult.Failed(BrowserOpenError.Unavailable) }
                if (token != generation) return@launch
                when (result) {
                    BrowserOpenResult.Dispatched -> {
                        mutableState.value = UpdateUiState.Idle
                        mutableDownloadState.value = UpdateDownloadState.Idle
                        eventChannel.trySend(UpdateEvent.BrowserDispatched)
                    }
                    is BrowserOpenResult.Failed -> mutableState.value = available.copy(browserError = result.reason)
                }
            } finally {
                if (token == generation && (state.value as? UpdateUiState.Available)?.opening == true) {
                    mutableState.value = available
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (token == generation && (state.value as? UpdateUiState.Available)?.opening == true) {
                    mutableState.value = available
                }
            }
            job.start()
        }
    }

    /** Starts only from Idle or Failed. Repeated clicks never start parallel downloads. */
    public fun startDownload() {
        val source = downloader ?: return
        if (disposed || !scope.isActive || downloadBusy) return
        val available = state.value as? UpdateUiState.Available ?: return
        if (available.opening) return
        if (downloadState.value !is UpdateDownloadState.Idle && downloadState.value !is UpdateDownloadState.Failed) return
        downloadCooldown?.takeIf { it.retryAt?.let { at -> at > Clock.System.now() } == true }?.let {
            mutableDownloadState.value = UpdateDownloadState.Failed(it)
            return
        }
        val token = ++generation
        mutableState.value = available.copy(browserError = null)
        mutableDownloadState.value = UpdateDownloadState.Downloading(DownloadProgress(0, available.release.packageSize))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var completedFile: DownloadedFile? = null
            var delivered = false
            try {
                val result = source.download(available.release) { progress ->
                    // Download callbacks may run on IO. Serialize state changes on the controller's scope.
                    scope.launch {
                        if (token == generation && isDownloadProgressState()) {
                            mutableDownloadState.value = when (progress.phase) {
                                DownloadPhase.Downloading -> UpdateDownloadState.Downloading(progress)
                                DownloadPhase.Finalizing -> UpdateDownloadState.Finalizing(progress)
                            }
                        }
                    }
                }
                if (result is DownloadResult.Completed) completedFile = result.file
                currentCoroutineContext().ensureActive()
                if (token != generation) return@launch
                when (result) {
                    is DownloadResult.Completed -> {
                        mutableDownloadState.value = UpdateDownloadState.Completed(result.file)
                        delivered = true
                    }
                    is DownloadResult.Failed -> {
                        downloadCooldown = result.error.takeIf { it.retryAt != null }
                        mutableDownloadState.value = UpdateDownloadState.Failed(result.error)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) mutableDownloadState.value = UpdateDownloadState.Failed(DownloadError(DownloadErrorKind.Network))
            } finally {
                if (!delivered && completedFile != null) {
                    withContext(NonCancellable) { runCatching { source.delete(completedFile) } }
                }
            }
        }
        downloadTask = job
        job.invokeOnCompletion {
            if (downloadTask === job && (mutableDownloadState.value is UpdateDownloadState.Cancelling ||
                (token == generation && isDownloadProgressState()))) {
                mutableDownloadState.value = UpdateDownloadState.Idle
            }
        }
        job.start()
    }

    public fun retryDownload(): Unit = startDownload()

    /** Returns to the available release only after the download has finished cleaning up. */
    public fun cancelDownload() {
        if (!downloadBusy || downloadState.value is UpdateDownloadState.Cancelling) return
        generation++
        mutableDownloadState.value = UpdateDownloadState.Cancelling
        downloadTask?.cancel()
    }

    /** Checks file existence before invoking a caller's explicit completion action. */
    public fun performCompletedAction(action: CompletedDownloadAction) {
        if (disposed || !scope.isActive || downloadBusy) return
        val completed = downloadState.value as? UpdateDownloadState.Completed ?: return
        if (completed.actionRunning) return
        val token = ++generation
        mutableDownloadState.value = completed.copy(actionRunning = true, actionError = null)
        task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val error = if (!completed.file.exists()) CompletedActionError.FileMissing
                    else if (action.perform(completed.file)) null else CompletedActionError.Failed
                if (token == generation) mutableDownloadState.value = completed.copy(actionError = error)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) mutableDownloadState.value = completed.copy(actionError = CompletedActionError.Failed)
            } finally {
                if (token == generation && (downloadState.value as? UpdateDownloadState.Completed)?.actionRunning == true) {
                    mutableDownloadState.value = completed
                }
            }
        }.also { job ->
            // A lazy task can be cancelled before its body (and finally) ever runs.
            job.invokeOnCompletion {
                if (token == generation && (downloadState.value as? UpdateDownloadState.Completed)?.actionRunning == true) {
                    mutableDownloadState.value = completed
                }
            }
            job.start()
        }
    }

    private fun isDownloadProgressState(): Boolean = downloadState.value is UpdateDownloadState.Downloading ||
        downloadState.value is UpdateDownloadState.Finalizing

    public fun dismiss() {
        generation++
        task?.cancel()
        if (downloadBusy) {
            mutableDownloadState.value = UpdateDownloadState.Cancelling
            downloadTask?.cancel()
        } else mutableDownloadState.value = UpdateDownloadState.Idle
        mutableState.value = UpdateUiState.Idle
    }

    public fun dispose() {
        disposed = true
        dismiss()
        eventChannel.close()
    }
}

/** Changing either dependency disposes the previous controller and cancels its operations. */
@Composable
public fun rememberUpdateController(checker: UpdateChecker, browserOpener: BrowserOpener): UpdateController {
    val scope = rememberCoroutineScope()
    val controller = remember(checker, browserOpener) { UpdateController(checker, browserOpener, scope) }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    return controller
}

/** The caller owns downloader and must close it when its owning lifecycle ends. */
@Composable
public fun rememberUpdateController(checker: UpdateChecker, browserOpener: BrowserOpener, downloader: UpdateDownloader): UpdateController {
    val scope = rememberCoroutineScope()
    val controller = remember(checker, browserOpener, downloader) { UpdateController(checker, browserOpener, downloader, scope) }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    return controller
}
