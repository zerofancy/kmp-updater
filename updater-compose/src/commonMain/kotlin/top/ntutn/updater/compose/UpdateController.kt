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

/** Call on the UI thread. Owns tasks in [scope], but does not own or close [checker]. */
public class UpdateController(
    private val checker: UpdateChecker,
    private val browserOpener: BrowserOpener,
    private val scope: CoroutineScope,
) {
    public val config: UpdaterConfig get() = checker.config
    private val mutableState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    public val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()
    private val mutableResult = MutableStateFlow<UpdateCheckResult?>(null)
    public val lastResult: StateFlow<UpdateCheckResult?> = mutableResult.asStateFlow()
    private val eventChannel = Channel<UpdateEvent>(Channel.BUFFERED)
    public val events: Flow<UpdateEvent> = eventChannel.receiveAsFlow()
    private var task: Job? = null
    private var generation = 0L
    private var disposed = false
    // Retained across dismissal so closing a 429 message cannot bypass Retry-After.
    private var cooldown: UpdateCheckResult.Failed? = null

    public fun check(trigger: CheckTrigger = CheckTrigger.Manual) {
        if (disposed || !scope.isActive) return
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
        if (disposed || !scope.isActive) return
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
                        eventChannel.trySend(UpdateEvent.BrowserDispatched)
                    }
                    is BrowserOpenResult.Failed -> mutableState.value = available.copy(browserError = result.reason)
                }
            } finally {
                if (token == generation && (state.value as? UpdateUiState.Available)?.opening == true) {
                    mutableState.value = available
                }
            }
        }.also { it.start() }
    }

    public fun dismiss() {
        generation++
        task?.cancel()
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
