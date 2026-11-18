package top.ntutn.updater.compose

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.ntutn.updater.AndroidInstallResult
import top.ntutn.updater.AndroidInstallSettingsResult
import top.ntutn.updater.UpdateOs
import top.ntutn.updater.openAndroidInstallPermissionSettings
import top.ntutn.updater.requestAndroidUpdateInstall

/** Keep mounted across foreground changes. Pass whether [activity] is resumed as [foreground]. */
@Composable
public fun AndroidUpdateHost(
    controller: UpdateController,
    activity: Activity,
    foreground: Boolean,
    strings: UpdateStrings = UpdateStrings.Chinese,
    downloadStrings: DownloadStrings = DownloadStrings.Chinese,
    installStrings: AndroidInstallStrings = AndroidInstallStrings.Chinese,
) {
    val state by controller.state.collectAsState()
    val available = state as? UpdateUiState.Available
    if (!controller.downloadEnabled || available == null) {
        if (foreground) UpdateHost(controller, strings)
        return
    }

    val download by controller.downloadState.collectAsState()
    val completed = download as? UpdateDownloadState.Completed
    val release = available.release
    if (completed == null || release.target.os != UpdateOs.Android || release.packageType != "apk") {
        if (foreground) DownloadDialog(controller, available, download, strings, downloadStrings)
        return
    }

    key(controller, activity, release, completed.file) {
        val completion = rememberAndroidInstallCompletion(activity, foreground, completed, installStrings, controller::dismiss)
        if (foreground) DownloadDialog(controller, available, completed, strings, downloadStrings, completion)
    }
}

@Composable
private fun rememberAndroidInstallCompletion(
    activity: Activity,
    foreground: Boolean,
    completed: UpdateDownloadState.Completed,
    strings: AndroidInstallStrings,
    dismiss: () -> Unit,
): DownloadCompletion? {
    // Retain permission and handoff feedback while the Android screen is open.
    var installResult by remember { mutableStateOf<AndroidInstallResult?>(null) }
    var settingsResult by remember { mutableStateOf<AndroidInstallSettingsResult?>(null) }
    if (!foreground) return null

    // Only the pending preparation belongs to this foreground composition.
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var screenDispatched by remember { mutableStateOf(false) }

    return DownloadCompletion(
        label = if (preparing) strings.preparing else strings.install,
        enabled = !preparing && !screenDispatched && !completed.actionRunning,
        onClick = {
            if (scope.isActive && !preparing && !screenDispatched && !completed.actionRunning) {
                preparing = true
                installResult = null
                settingsResult = null
                scope.launch {
                    try {
                        val result = requestAndroidUpdateInstall(activity, completed.file)
                        screenDispatched = result == AndroidInstallResult.Dispatched
                        installResult = result
                    } finally {
                        preparing = false
                    }
                }
            }
        },
        details = {
            when (val result = installResult) {
                AndroidInstallResult.Dispatched -> Text(strings.dispatched)
                AndroidInstallResult.PermissionRequired -> {
                    Text(strings.permissionRequired)
                    TextButton(
                        enabled = !preparing && !screenDispatched,
                        onClick = {
                            if (scope.isActive && !preparing && !screenDispatched &&
                                installResult == AndroidInstallResult.PermissionRequired
                            ) {
                                val status = openAndroidInstallPermissionSettings(activity)
                                settingsResult = status
                                screenDispatched = status == AndroidInstallSettingsResult.Opened
                            }
                        },
                    ) { Text(strings.openPermissionSettings) }
                    settingsResult?.let(strings::error)?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }
                is AndroidInstallResult.Failed -> Text(strings.error(result.reason), color = MaterialTheme.colorScheme.error)
                null -> Unit
            }
        },
        onDismiss = {
            scope.cancel()
            dismiss()
        },
    )
}
