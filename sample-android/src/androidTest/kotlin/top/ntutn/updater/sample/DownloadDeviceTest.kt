package top.ntutn.updater.sample

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real local streaming downloads through adb reverse tcp:18082 tcp:18082. No package is installed. */
@RunWith(AndroidJUnit4::class)
class DownloadDeviceTest {
    @Suppress("DEPRECATION")
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun configure(software: String, failBrowser: Boolean = false) {
        compose.onNodeWithText("服务地址").performTextReplacement("http://127.0.0.1:18082")
        compose.onNodeWithText("软件内部名").performTextReplacement(software)
        compose.onNodeWithText("本地 versionCode").performTextReplacement("1")
        compose.onNodeWithText("包类型").performTextReplacement("apk")
        val enabled = compose.onAllNodes(hasTestTag("in-app-download") and isOn()).fetchSemanticsNodes().isNotEmpty()
        if (!enabled) compose.onNodeWithTag("in-app-download").performScrollTo().performClick()
        val browserFails = compose.onAllNodes(hasTestTag("fail-browser") and isOn()).fetchSemanticsNodes().isNotEmpty()
        if (browserFails != failBrowser) compose.onNodeWithTag("fail-browser").performScrollTo().performClick()
        compose.onNodeWithText("应用配置").performScrollTo().performClick()
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        awaitText("发现新版本")
    }

    private fun awaitText(text: String, timeout: Long = 20000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { parcel ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel).bufferedReader().readText()
        }

    private fun screenshot(name: String) {
        shell("mkdir -p /data/local/tmp/kmp-updater-verification")
        shell("screencap -p /data/local/tmp/kmp-updater-verification/$name.png")
    }

    private fun completedFiles(): List<File> = context.filesDir.walkTopDown().filter { it.extension == "apk" }.toList()

    private fun assertNewSlowDownload(existing: Set<String>) {
        val created = completedFiles().filter { it.absolutePath !in existing }
        assertEquals("This attempt must create exactly one completed file", 1, created.size)
        val file = created.single()
        val expectedSize = 4L * 1024 * 1024
        assertEquals(expectedSize, file.length())
        var offset = 0L
        val buffer = ByteArray(64 * 1024)
        file.inputStream().buffered().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                for (index in 0 until count) {
                    // The slow fixture repeats the first 64 KiB of its deterministic payload.
                    val expected = (((offset + index) % (64 * 1024)) % 251).toByte()
                    if (buffer[index] != expected) fail("Incorrect fixture byte at ${offset + index}")
                }
                offset += count
            }
        }
        assertEquals(expectedSize, offset)
        assertTrue(context.filesDir.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test fun knownSizeDownloadWritesExactFileAndRedirectWorks() {
        val existing = completedFiles().map { it.absolutePath }.toSet()
        configure("sdk-known")
        compose.onNodeWithText("下载更新").performClick()
        awaitText("下载完成")
        val file = completedFiles().first { it.absolutePath !in existing }
        assertEquals(1024L * 1024, file.length())
        val bytes = file.readBytes()
        assertTrue("Fixture bytes must match the streamed response", bytes.indices.all { bytes[it] == (it % 251).toByte() })
        // The SDK canonicalizes its root; Android may expose filesDir through a path alias.
        compose.onNodeWithText(file.canonicalPath).assertExists()
        screenshot("android-download-completed")
        compose.onNodeWithText("完成").performClick()
        configure("sdk-redirect")
        compose.onNodeWithText("下载更新").performClick()
        awaitText("下载完成")
    }

    @Test fun unknownSizeCanCancelAndDownloadAgain() {
        val existing = completedFiles().map { it.absolutePath }.toSet()
        configure("sdk-slow")
        compose.onNodeWithText("下载更新").performClick()
        awaitText("服务器未提供总大小")
        screenshot("android-download-unknown")
        compose.onNodeWithText("取消下载").performClick()
        awaitText("发现新版本")
        assertTrue(context.filesDir.walkTopDown().none { it.name.endsWith(".part") })
        compose.onNodeWithText("下载更新").performClick()
        awaitText("下载完成")
        assertNewSlowDownload(existing)
    }

    @Test fun htmlPageRejectsDirectDownloadAndKeepsBrowserFallback() {
        configure("sdk-html", failBrowser = true)
        compose.onNodeWithText("下载更新").performClick()
        awaitText("该地址不支持直接下载")
        compose.onNodeWithText("浏览器下载").performClick()
        awaitText("无法打开浏览器")
        compose.onNodeWithText("http://127.0.0.1:18082/download/html?platform=Android&token=a%2Bb%26c").assertExists()
        screenshot("android-download-html-fallback")
        compose.onNodeWithText("重新下载").performClick()
        awaitText("该地址不支持直接下载")
    }

    @Test fun httpFailureCanRetryWithoutLeavingUpdateDialog() {
        configure("sdk-failed")
        compose.onNodeWithText("下载更新").performClick()
        awaitText("下载未完成")
        compose.onNodeWithText("重新下载").performClick()
        awaitText("下载未完成")
        assertTrue(context.filesDir.walkTopDown().none { it.name.endsWith(".part") })
        screenshot("android-download-http-failed")
    }

    @Test fun backgroundReturnRetainsActiveDownload() {
        val existing = completedFiles().map { it.absolutePath }.toSet()
        configure("sdk-slow")
        compose.onNodeWithText("下载更新").performClick()
        awaitText("正在下载更新")
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1000)
        shell("am start -f 0x20000 -n top.ntutn.updater.sample/.MainActivity")
        awaitText("下载完成")
        assertNewSlowDownload(existing)
        screenshot("android-download-background-return")
    }
}
