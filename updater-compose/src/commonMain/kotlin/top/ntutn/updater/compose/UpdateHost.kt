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
import kotlinx.coroutines.delay
import top.ntutn.updater.*
import kotlin.time.Clock

/** Mount while foregrounded. Does not start checks or collect the controller's one-shot events. */
@Composable
public fun UpdateHost(controller: UpdateController, strings: UpdateStrings = UpdateStrings.Chinese) {
    val state by controller.state.collectAsState()
    val closeOnEscape = Modifier.onPreviewKeyEvent {
        if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { controller.dismiss(); true } else false
    }
    when (val current = state) {
        UpdateUiState.Idle -> Unit
        is UpdateUiState.Checking -> if (current.trigger == CheckTrigger.Manual) {
            // A compact nonmodal indicator; callers can place the host inside their Scaffold.
            Surface(tonalElevation = 4.dp, modifier = Modifier.padding(12.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text(strings.checking)
                    TextButton(onClick = controller::dismiss) { Text(strings.close) }
                }
            }
        }
        is UpdateUiState.Available -> {
            val release = current.release
            AlertDialog(
                modifier = closeOnEscape,
                onDismissRequest = controller::dismiss,
                title = { Text(strings.foundUpdate) },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${release.softwareDisplayName} ${release.version.name}", style = MaterialTheme.typography.titleMedium)
                        Text("${strings.current} ${controller.config.currentVersion.name} → ${strings.latest} ${release.version.name}")
                        release.packageSize?.let { Text(formatPackageSize(it)) }
                        release.description?.let { Text(it) }
                        Text(strings.browserHint, style = MaterialTheme.typography.bodySmall)
                        if (current.browserError != null) {
                            Text(strings.browserFailed, color = MaterialTheme.colorScheme.error)
                            SelectionContainer { Text(release.downloadUrl) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = controller::openDownload, enabled = !current.opening) {
                        Text(when { current.opening -> strings.opening; current.browserError != null -> strings.retryOpen; else -> strings.download })
                    }
                },
                dismissButton = { TextButton(onClick = controller::dismiss) { Text(strings.later) } },
            )
        }
        is UpdateUiState.Feedback -> {
            val failure = current.result as? UpdateCheckResult.Failed
            var now by remember(current) { mutableStateOf(Clock.System.now()) }
            val retryAt = failure?.error?.retryAt
            LaunchedEffect(retryAt) {
                while (retryAt != null && now < retryAt) { delay(250); now = Clock.System.now() }
            }
            val limited = retryAt != null && now < retryAt
            val message = when (val result = current.result) {
                is UpdateCheckResult.UpToDate -> if (result.latest.code < controller.config.currentVersion.code) strings.noUpdate else strings.upToDate
                UpdateCheckResult.NoMatchingPackage -> strings.noPackage
                else -> strings.failed
            }
            AlertDialog(
                modifier = closeOnEscape,
                onDismissRequest = controller::dismiss,
                title = { Text(message) },
                text = if (limited) ({ Text(strings.cooldown) }) else null,
                confirmButton = { TextButton(onClick = controller::dismiss) { Text(strings.close) } },
                dismissButton = if (failure != null) ({
                    TextButton(onClick = { controller.check() }, enabled = !limited) { Text(strings.retry) }
                }) else null,
            )
        }
    }
}

public fun formatPackageSize(bytes: Long): String {
    require(bytes >= 0)
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    val tenths = kotlin.math.round(value * 10).toLong()
    return if (index == 0) "$bytes B" else "${tenths / 10}.${tenths % 10} ${units[index]}"
}
