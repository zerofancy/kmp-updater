package top.ntutn.updater.sample

import kotlinx.coroutines.runBlocking
import top.ntutn.updater.*

/** Run with -Djava.awt.headless=true to exercise the actual Linux xdg-open fallback. */
fun main() = runBlocking {
    check(System.getProperty("os.name") == "Linux")
    check(java.awt.GraphicsEnvironment.isHeadless())
    val opener = createDesktopBrowserOpener(allowInsecureHttp = true)
    check(opener.open("file:///tmp/app") == BrowserOpenResult.Failed(BrowserOpenError.UnsafeUrl))
    val result = opener.open("http://127.0.0.1:18082/browser?platform=Linux-fallback&token=a%2Bb%26c")
    check(result == BrowserOpenResult.Dispatched) { "xdg-open fallback failed: $result" }
    println("Linux headless xdg-open fallback: Dispatched; unsafe scheme: rejected")
}
