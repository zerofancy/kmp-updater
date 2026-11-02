package top.ntutn.updater.sample

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.runner.RunWith

/** Uses a real local updater-server fixture via adb reverse tcp:18082 tcp:18082. */
@RunWith(AndroidJUnit4::class)
class UpdateDeviceTest {
    // Real-time effects keep the external browser launch running while this test polls outside Compose.
    @Suppress("DEPRECATION")
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun configure(code: String = "1", type: String = "apk", software: String = "sdk-demo", failBrowser: Boolean = false) {
        compose.onNodeWithText("服务地址").performTextReplacement("http://127.0.0.1:18082")
        compose.onNodeWithText("软件内部名").performTextReplacement(software)
        compose.onNodeWithText("本地 versionCode").performTextReplacement(code)
        compose.onNodeWithText("包类型").performTextReplacement(type)
        if (failBrowser) compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("应用配置").performScrollTo().performClick()
    }
    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        if (text in listOf("检查更新", "静默检测")) node.performScrollTo()
        node.performClick()
    }
    private fun awaitText(text: String) {
        compose.waitUntil(15000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) {
        Thread.sleep(500) // Let system Activity / dialog animations settle before the evidence capture.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf("mkdir -p /data/local/tmp/kmp-updater-verification", "screencap -p /data/local/tmp/kmp-updater-verification/$name.png").forEach { command ->
            automation.executeShellCommand(command).use { parcel ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel).readBytes()
            }
        }
    }
    @Test fun updateShowsPlainTextAndCanDismiss() {
        configure(); click("检查更新"); awaitText("发现新版本")
        compose.onNodeWithText("SDK 联调示例 1.2.0").assertIsDisplayed()
        compose.onNodeWithText("纯文本 <literal>", substring = true).assertExists()
        screenshot("android-update")
        click("暂不更新")
        compose.onNodeWithText("发现新版本").assertDoesNotExist()
        click("检查更新"); awaitText("发现新版本")
    }
    @Test fun equalAndLowerVersionsDoNotUpdate() {
        configure("10"); click("检查更新"); awaitText("当前已是最新版本"); click("关闭")
        configure("11"); click("检查更新"); awaitText("暂无可用更新")
        screenshot("android-no-update")
    }
    @Test fun noMatchingPackageIsDistinct() {
        configure(type = "zip"); click("检查更新"); awaitText("暂无适用于当前安装版本的更新包")
        screenshot("android-no-package")
    }
    @Test fun manualFailureAndSilentFailure() {
        configure(software = "sdk-missing"); click("检查更新"); awaitText("检查更新失败，请稍后重试")
        screenshot("android-check-failed"); click("关闭")
        click("静默检测"); awaitText("检测结果：Failed")
        compose.onNodeWithText("检查更新失败，请稍后重试").assertDoesNotExist()
    }
    @Test fun browserFailureRetainsReleaseAndRetry() {
        configure(failBrowser = true); click("检查更新"); awaitText("发现新版本"); click("前往下载")
        awaitText("无法打开浏览器")
        compose.onNodeWithText("http://127.0.0.1:18082/download?platform=Android&token=a%2Bb%26c").assertExists()
        screenshot("android-browser-failed")
        click("重试打开"); awaitText("无法打开浏览器")
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun realBrowserHandoffAndReturn() {
        configure(); click("检查更新"); awaitText("发现新版本")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Use a real input event: Espresso waits for the tested Activity to resume after an external launch.
        val center = compose.onNodeWithText("前往下载").fetchSemanticsNode().boundsInWindow.center
        val location = IntArray(2)
        compose.runOnIdle {
            android.view.inspector.WindowInspector.getGlobalWindowViews().first { it.hasWindowFocus() }.getLocationOnScreen(location)
        }
        android.util.Log.i("UpdaterVerification", "center=$center location=${location.toList()}")
        screenshot("android-before-browser")
        instrumentation.uiAutomation.executeShellCommand("input tap ${(center.x + location[0]).toInt()} ${(center.y + location[1]).toInt()}").use { parcel ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel).readBytes()
        }
        // External browser navigation is outside Compose's idling resources.
        Thread.sleep(2500)
        val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities").use { parcel ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel).bufferedReader().readText()
        }
        android.util.Log.i("UpdaterVerification", dump.lines().filter { "ResumedActivity" in it }.joinToString())
        screenshot("android-after-browser-tap")
        Assert.assertTrue("A browser must become resumed", dump.lines().any { "ResumedActivity" in it && "top.ntutn.updater.sample" !in it })
        screenshot("android-browser-handoff")
        instrumentation.uiAutomation.executeShellCommand("am start -f 0x20000 -n top.ntutn.updater.sample/.MainActivity").use { parcel ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel).readBytes()
        }
        Thread.sleep(500)
        awaitText("已交给浏览器处理")
        compose.onNodeWithText("发现新版本").assertDoesNotExist()
        screenshot("android-return")
    }
}
