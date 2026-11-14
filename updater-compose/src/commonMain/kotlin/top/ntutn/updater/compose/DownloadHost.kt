package top.ntutn.updater.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import top.ntutn.updater.*
import kotlin.time.Clock

/** Download-enabled host. Keep the controller mounted when hiding this host on background changes. */
@Composable
public fun UpdateHost(
    controller: UpdateController,
    strings: UpdateStrings,
    downloadStrings: DownloadStrings,
    completedAction: CompletedDownloadAction? = null,
) {
    val state by controller.state.collectAsState()
    val available = state as? UpdateUiState.Available
    if (controller.downloadEnabled && available != null) {
        val download by controller.downloadState.collectAsState()
        DownloadDialog(controller, available, download, strings, downloadStrings, completedAction)
    } else UpdateHost(controller, strings)
}

@Composable
private fun DownloadDialog(
    controller: UpdateController,
    available: UpdateUiState.Available,
    download: UpdateDownloadState,
    strings: UpdateStrings,
    downloads: DownloadStrings,
    completedAction: CompletedDownloadAction?,
) {
    val active = download is UpdateDownloadState.Downloading || download is UpdateDownloadState.Finalizing ||
        download is UpdateDownloadState.Cancelling
    val completed = download as? UpdateDownloadState.Completed
    val failure = download as? UpdateDownloadState.Failed
    var now by remember(failure) { mutableStateOf(Clock.System.now()) }
    val retryAt = failure?.error?.retryAt
    LaunchedEffect(retryAt) {
        while (retryAt != null && now < retryAt) { delay(250); now = Clock.System.now() }
    }
    val limited = retryAt != null && now < retryAt
    val dismiss = { if (active) controller.cancelDownload() else controller.dismiss() }
    AlertDialog(
        modifier = Modifier.onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { dismiss(); true } else false
        },
        onDismissRequest = dismiss,
        properties = DialogProperties(dismissOnClickOutside = !active),
        title = {
            Text(when (download) {
                is UpdateDownloadState.Downloading -> downloads.downloading
                is UpdateDownloadState.Finalizing -> downloads.finalizing
                UpdateDownloadState.Cancelling -> downloads.cancelling
                is UpdateDownloadState.Completed -> downloads.completed
                is UpdateDownloadState.Failed -> downloads.failed
                UpdateDownloadState.Idle -> strings.foundUpdate
            })
        },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val release = available.release
                Text("${release.softwareDisplayName} ${release.version.name}", style = MaterialTheme.typography.titleMedium)
                Text("${strings.current} ${controller.config.currentVersion.name} → ${strings.latest} ${release.version.name}")
                when (download) {
                    UpdateDownloadState.Idle -> {
                        release.packageSize?.let { Text(formatPackageSize(it)) }
                        release.description?.let { Text(it) }
                        Text(downloads.saveHint, style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateDownloadState.Downloading -> DownloadProgressContent(download.progress, downloads)
                    is UpdateDownloadState.Finalizing -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(downloads.finalizing)
                    }
                    UpdateDownloadState.Cancelling -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(downloads.cancelling)
                    }
                    is UpdateDownloadState.Failed -> {
                        Text(downloads.error(download.error.kind), color = MaterialTheme.colorScheme.error)
                        if (limited) Text(strings.cooldown)
                    }
                    is UpdateDownloadState.Completed -> {
                        Text("${download.file.fileName} · ${formatPackageSize(download.file.sizeBytes)}")
                        SelectionContainer { Text(download.file.localPath) }
                        download.actionError?.let {
                            Text(
                                if (it == CompletedActionError.FileMissing) downloads.fileMissing else downloads.actionFailed,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (available.browserError != null) {
                    Text(strings.browserFailed, color = MaterialTheme.colorScheme.error)
                    SelectionContainer { Text(release.downloadUrl) }
                }
            }
        },
        confirmButton = {
            when {
                active -> TextButton(onClick = controller::cancelDownload, enabled = download !is UpdateDownloadState.Cancelling) {
                    Text(if (download is UpdateDownloadState.Cancelling) downloads.cancelling else downloads.cancel)
                }
                completed != null && completedAction != null -> TextButton(
                    onClick = { controller.performCompletedAction(completedAction) }, enabled = !completed.actionRunning,
                ) { Text(if (completed.actionRunning) downloads.actionRunning else completedAction.label) }
                completed != null -> TextButton(onClick = controller::dismiss) { Text(downloads.done) }
                else -> TextButton(onClick = controller::startDownload, enabled = !limited && !available.opening) {
                    Text(if (failure != null) downloads.retry else downloads.download)
                }
            }
        },
        dismissButton = if (active || (completed != null && completedAction == null)) null else ({
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                if (completed == null) TextButton(onClick = controller::openDownload, enabled = !available.opening) {
                    Text(if (available.opening) strings.opening else downloads.browserDownload)
                }
                TextButton(onClick = controller::dismiss) {
                    Text(if (completed != null) downloads.done else if (failure != null) strings.close else strings.later)
                }
            }
        }),
    )
}

@Composable
private fun DownloadProgressContent(progress: DownloadProgress, strings: DownloadStrings) {
    val total = progress.totalBytes?.takeIf { it > 0 }
    if (total == null) {
        Text("${strings.downloaded} ${formatPackageSize(progress.downloadedBytes.coerceAtLeast(0))}")
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(strings.unknownSize, style = MaterialTheme.typography.bodySmall)
    } else {
        val fraction = (progress.downloadedBytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        Text("${formatPackageSize(progress.downloadedBytes.coerceAtLeast(0))} / ${formatPackageSize(total)} · ${(fraction * 100).toInt()}%")
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
}
