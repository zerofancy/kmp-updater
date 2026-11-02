package top.ntutn.updater

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.TimeUnit

internal actual fun updateHttpEngine(): HttpClientEngine = OkHttp.create()
public actual fun detectUpdateTarget(): TargetDetection = desktopUpdateTarget(System.getProperty("os.name"), System.getProperty("os.arch"))

/** Uses the system URL association. The default browser may redirect to another registered application. */
public fun createDesktopBrowserOpener(allowInsecureHttp: Boolean = false): BrowserOpener = BrowserOpener { url ->
    if (!isAllowedWebUrl(url, allowInsecureHttp)) return@BrowserOpener BrowserOpenResult.Failed(BrowserOpenError.UnsafeUrl)
    runInterruptible(Dispatchers.IO) {
        val dispatched = runCatching {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) false
            else { Desktop.getDesktop().browse(URI(url)); true }
        }.getOrDefault(false)
        if (dispatched) BrowserOpenResult.Dispatched else {
            val command = when ((detectUpdateTarget() as? TargetDetection.Supported)?.target?.os) {
                UpdateOs.Linux -> "xdg-open"
                UpdateOs.MacOS -> "/usr/bin/open"
                else -> null
            }
            val success = command != null && runCatching {
                val process = ProcessBuilder(command, url).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                try { process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0 }
                finally { if (process.isAlive) process.destroy() }
            }.getOrDefault(false)
            if (success) BrowserOpenResult.Dispatched else BrowserOpenResult.Failed(BrowserOpenError.Unavailable)
        }
    }
}
