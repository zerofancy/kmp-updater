package top.ntutn.updater.sample

import androidx.compose.runtime.*
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities
import java.awt.Desktop
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import top.ntutn.updater.*
import top.ntutn.updater.compose.CompletedDownloadAction

fun main() = application {
    val target = (detectUpdateTarget() as? TargetDetection.Supported)?.target ?: error("Unsupported platform")
    Window(onCloseRequest = ::exitApplication, title = "KMP Updater Sample", state = rememberWindowState(width = 720.dp, height = 800.dp)) {
        var foreground by remember { mutableStateOf(true) }
        DisposableEffect(window) {
            val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            val listener = PropertyChangeListener {
                SwingUtilities.invokeLater {
                    foreground = generateSequence(manager.activeWindow) { it.owner }.any { it == window }
                }
            }
            manager.addPropertyChangeListener("activeWindow", listener)
            onDispose { manager.removePropertyChangeListener("activeWindow", listener) }
        }
        val opener = remember { createDesktopBrowserOpener(allowInsecureHttp = true) }
        val directory = remember { File(System.getProperty("user.home"), ".kmp-updater-sample/updates") }
        val downloaderFactory = remember(directory) {
            { options: DownloadOptions -> createDesktopUpdateDownloader(directory, options) }
        }
        val completedAction = remember {
            CompletedDownloadAction("打开下载目录") { file ->
                withContext(Dispatchers.IO) {
                    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
                    if (desktop?.isSupported(Desktop.Action.OPEN) != true) false
                    else { desktop.open(File(file.localPath).parentFile); true }
                }
            }
        }
        SampleApp(target, opener, System.getenv("UPDATER_SAMPLE_URL") ?: "https://zp.ntutn.top", foreground,
            downloaderFactory, completedAction)
    }
}
