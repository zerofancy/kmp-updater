package top.ntutn.updater.sample

import androidx.compose.runtime.*
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import top.ntutn.updater.*

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
        SampleApp(target, opener, System.getenv("UPDATER_SAMPLE_URL") ?: "https://zp.ntutn.top", foreground)
    }
}
