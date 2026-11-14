package top.ntutn.updater.compose

import top.ntutn.updater.DownloadError
import top.ntutn.updater.DownloadProgress
import top.ntutn.updater.DownloadedFile

/** Download state belongs to the release currently shown by UpdateUiState.Available. */
public sealed interface UpdateDownloadState {
    public data object Idle : UpdateDownloadState
    public data class Downloading(public val progress: DownloadProgress) : UpdateDownloadState
    public data object Cancelling : UpdateDownloadState
    public data class Finalizing(public val progress: DownloadProgress) : UpdateDownloadState
    public data class Completed(
        public val file: DownloadedFile,
        public val actionRunning: Boolean = false,
        public val actionError: CompletedActionError? = null,
    ) : UpdateDownloadState
    public data class Failed(public val error: DownloadError) : UpdateDownloadState
}

public enum class CompletedActionError { FileMissing, Failed }

/** A caller-owned action, such as opening a folder. True means the handoff was accepted, not installed. */
public data class CompletedDownloadAction(
    public val label: String,
    public val perform: suspend (DownloadedFile) -> Boolean,
)
