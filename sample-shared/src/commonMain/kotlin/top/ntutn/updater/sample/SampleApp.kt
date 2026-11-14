package top.ntutn.updater.sample

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import top.ntutn.updater.*
import top.ntutn.updater.compose.*

@Composable
fun SampleApp(
    target: UpdateTarget,
    browserOpener: BrowserOpener,
    baseUrl: String = "https://zp.ntutn.top",
    foreground: Boolean = true,
    downloaderFactory: ((DownloadOptions) -> UpdateDownloader)? = null,
    completedAction: CompletedDownloadAction? = null,
) {
    val focusManager = LocalFocusManager.current
    var server by remember { mutableStateOf(baseUrl) }
    var software by remember { mutableStateOf("sdk-demo") }
    var code by remember { mutableStateOf("1") }
    var packageType by remember { mutableStateOf(if (target.os == UpdateOs.Android) "apk" else "appimage") }
    var activeConfig by remember { mutableStateOf<UpdaterConfig?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var simulateBrowserFailure by remember { mutableStateOf(false) }
    var activeFailure by remember { mutableStateOf(false) }
    var inAppDownload by remember { mutableStateOf(downloaderFactory != null) }
    var activeInAppDownload by remember { mutableStateOf(false) }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("KMP Updater 示例", style = MaterialTheme.typography.headlineMedium)
                Text("${target.os} / ${target.arch} · 应用内 / 浏览器下载")
                OutlinedTextField(server, { server = it }, label = { Text("服务地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(software, { software = it }, label = { Text("软件内部名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(code, { code = it }, label = { Text("本地 versionCode") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(packageType, { packageType = it }, label = { Text("包类型") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                if (downloaderFactory != null) {
                    Row { Checkbox(inAppDownload, { inAppDownload = it }, Modifier.testTag("in-app-download")); Text("应用内下载", Modifier.padding(top = 12.dp)) }
                }
                Row { Checkbox(simulateBrowserFailure, { simulateBrowserFailure = it }, Modifier.testTag("fail-browser")); Text("模拟浏览器不可用", Modifier.padding(top = 12.dp)) }
                Button(onClick = {
                    focusManager.clearFocus()
                    try {
                        activeFailure = simulateBrowserFailure
                        activeInAppDownload = inAppDownload
                        activeConfig = UpdaterConfig(server, software, UpdateVersion(code.toLong(), "本地 $code"), target, packageType,
                            allowInsecureHttp = server.startsWith("http://"))
                        formError = null
                    } catch (_: IllegalArgumentException) { formError = "请检查服务地址、软件名、版本号及包类型" }
                }) { Text("应用配置") }
                formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                activeConfig?.let { config ->
                    key(config, activeFailure, activeInAppDownload) {
                        ActiveUpdater(config, browserOpener, activeFailure, foreground,
                            downloaderFactory.takeIf { activeInAppDownload }, completedAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveUpdater(
    config: UpdaterConfig,
    browser: BrowserOpener,
    failBrowser: Boolean,
    foreground: Boolean,
    downloaderFactory: ((DownloadOptions) -> UpdateDownloader)?,
    completedAction: CompletedDownloadAction?,
) {
    val checker = remember(config) { createUpdateChecker(config) }
    DisposableEffect(checker) { onDispose { checker.close() } }
    val opener = remember(browser, failBrowser) {
        if (failBrowser) BrowserOpener { BrowserOpenResult.Failed(BrowserOpenError.Unavailable) } else browser
    }
    val downloader = remember(config, downloaderFactory) {
        downloaderFactory?.invoke(DownloadOptions(allowInsecureHttp = config.allowInsecureHttp))
    }
    DisposableEffect(downloader) { onDispose { downloader?.close() } }
    val controller = if (downloader != null) rememberUpdateController(checker, opener, downloader)
        else rememberUpdateController(checker, opener)
    val state by controller.state.collectAsState()
    val downloadState by controller.downloadState.collectAsState()
    val result by controller.lastResult.collectAsState()
    var event by remember { mutableStateOf("") }
    LaunchedEffect(controller) { controller.events.collect { event = "已交给浏览器处理" } }
    Text("当前配置：${config.software} / ${config.packageType} / ${config.currentVersion.code}")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { event = ""; controller.check() }, enabled = state !is UpdateUiState.Checking) { Text("检查更新") }
        OutlinedButton(onClick = { event = ""; controller.check(CheckTrigger.Silent) }) { Text("静默检测") }
    }
    Text("检测状态：${state::class.simpleName}")
    Text("检测结果：${result?.let { it::class.simpleName } ?: "未检测"}")
    if (downloader != null) Text("下载状态：${downloadState::class.simpleName}")
    if (event.isNotBlank()) Text(event)
    if (foreground) {
        if (downloader != null) UpdateHost(controller, UpdateStrings.Chinese, DownloadStrings.Chinese, completedAction)
        else UpdateHost(controller)
    }
}
