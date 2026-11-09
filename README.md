# KMP Updater

面向 Compose Multiplatform 的应用更新 SDK：查询最新版本、提示用户，点击后交给系统浏览器下载。
支持 Android（minSdk 24）及 JVM 桌面的 Linux、Windows、macOS；架构为 ARM64 / X86_64。
版本按正数 Long `versionCode` 比较，`versionName` 只展示，不解析 SemVer。

## 模块与依赖

- `updater` / `io.github.zerofancy:kmp-updater:0.1.1`：独立检测核心和平台浏览器适配，不依赖 Compose。
- `updater-compose` / `io.github.zerofancy:kmp-updater-compose:0.1.1`：可选 Material 3 提示与状态控制器，传递依赖核心。
- `sample-shared`、`sample-android`、`sample-desktop`：可配置的 Android / 桌面示例。

从 [GitHub Releases](https://github.com/zerofancy/kmp-updater/releases) 下载并解压 Maven 仓库压缩包，将解压目录加入 Gradle 的依赖仓库，例如 `maven { url = uri("/path/to/kmp-updater-0.1.1-maven") }`。也可从源码执行 `publishToMavenLocal`，然后在宿主启用 `mavenLocal()`。构建需要 JDK 21、Android SDK 37；
库 JVM 字节码目标为 11，桌面示例使用 JDK 21 打包。Compose Multiplatform 1.12.1、Material 3 1.9.0、
Kotlin 2.4.20 为本版验证基线，版本集中在 `gradle/libs.versions.toml`。

## 最小接入

```kotlin
commonMain.dependencies {
    implementation("io.github.zerofancy:kmp-updater-compose:0.1.1")
    // 无默认 UI 时只引用 kmp-updater。
}
```

宿主必须提供服务端软件内部名、本地版本、安装包类型及目标。服务地址可配置，遵循
[updater-server 更新 API](../updater-server/docs/update-api.html)，例如 `https://zp.ntutn.top`。

```kotlin
val detection = detectUpdateTarget()
val target = when (detection) {
    is TargetDetection.Supported -> detection.target
    TargetDetection.UnsupportedPlatform -> return // 宿主显示不支持，或显式配置受支持目标。
}
val config = UpdaterConfig(
    baseUrl = "https://zp.ntutn.top",
    software = "your-software",       // 替换为实际服务端标识
    currentVersion = UpdateVersion(100, "1.0.0"),
    target = target,
    packageType = "apk",             // 从构建 / 安装变体传入，桌面可能为 appimage / dmg / msi 等。
)
val checker = createUpdateChecker(config)
try {
    when (val result = checker.check()) {
        is UpdateCheckResult.Available -> showYourUpdateUi(result.release)
        is UpdateCheckResult.UpToDate -> showYourNoUpdateUi()
        UpdateCheckResult.NoMatchingPackage -> showYourNoPackageUi()
        is UpdateCheckResult.Failed -> handleError(result.error)
    }
} finally {
    checker.close()
}
```

这些 `showYour…` / `handleError` 为宿主自己的函数。查询只发 software、os、arch、packageType，
每次精确匹配一个序列；不会换包类型、跨架构求最高版本或自动降级。当前服务无通道、最低系统、
强制更新、签名兼容性字段，发布方应保证所选序列适合当前安装来源。
自动架构探测反映运行进程；Rosetta / Windows ARM 上的 x64 JVM 默认沿用进程架构，宿主可覆盖。
32 位 Android / ARMv7 / x86 不支持，不会发宽查询。

## Compose 提示

平台入口创建浏览器适配器：Android 使用 `createAndroidBrowserOpener(context)`，
桌面使用 `createDesktopBrowserOpener()`，再传给公共 UI。

```kotlin
@Composable
fun Updates(config: UpdaterConfig, browserOpener: BrowserOpener, foreground: Boolean) {
    val checker = remember(config) { createUpdateChecker(config) }
    DisposableEffect(checker) { onDispose { checker.close() } }
    val controller = rememberUpdateController(checker, browserOpener)
    val state by controller.state.collectAsState()

    Button(
        onClick = { controller.check(CheckTrigger.Manual) },
        enabled = state !is UpdateUiState.Checking,
    ) { Text("检查更新") }
    if (foreground) UpdateHost(controller, strings = UpdateStrings.Chinese)

    // 可选：宿主决定何时执行一次启动检测，不在重组中直接调用 check。
    // LaunchedEffect(controller) { controller.check(CheckTrigger.Silent) }
}
```

`UpdateHost` 应放在宿主 `MaterialTheme` 下，仅在前台挂载，后台结果会保留到恢复前台。
Android 示例按 Activity onStart/onStop 控制，桌面示例按当前 JVM 活跃窗口及其 owner 控制。
浏览器适配器、checker 保持稳定引用；更换配置或适配器会释放旧控制器、取消旧任务。
控制器操作须在 UI 线程调用；`dispose()` 不会关闭宿主拥有的 checker。

手动检测显示进度、无更新 / 失败反馈；静默检测只在有更新时弹窗。
重复检测合并；Silent 在途遇到 Manual 时提升反馈；关闭不持久忽略该版本。
`lastResult: StateFlow` 保留结果供诊断。浏览器接受请求后产生一次
`events` 的 `BrowserDispatched` 事件，单一消费者收集；它不表示下载或安装完成。

文案可用 `UpdateStrings.English` 或 `UpdateStrings.Chinese.copy(...)` 自定义。
说明优先使用纯文本，Markdown 仅原样展示；不执行 HTML、不自动激活说明内链接。
浏览器失败会保留更新弹窗，提供重试与可选择复制的地址。

## 网络、URL 与生命周期

默认只允许 HTTPS。`baseUrl` 是 origin，可带端口及根 `/`，不支持子路径、凭据、查询或片段。
开发环境需要显式 `allowInsecureHttp = true`，浏览器工厂也需设置相同选项。
Android 宿主还需允许测试地址的明文流量；示例仅 Debug manifest 启用，库不替宿主打开明文策略。
INTERNET 权限由核心 manifest 合并。下载 URL 可使用外部存储域名，scheme 仅 HTTP(S)。

总查询超时默认 10 秒，可传正有限 `kotlin.time.Duration`。查询不自动重试或跟随重定向；
响应读取上限 256 KiB。未知字段兼容，但版本、目标、数组和关键字段严格校验。
404 / NO_MATCHING_PACKAGE 独立返回，不误报“已是最新版”；其他失败由 `UpdateError.kind`
分类，并保留 HTTP status、服务错误码；429 的 Retry-After 保留在 retryAt 中，控制器遵守冷却。
取消传播 CancellationException；close 可重复调用，并取消该 checker 的在途请求。

可通过 `createUpdateChecker(config, client)` 注入 HttpClient；其生命周期由宿主管理。
注入客户端必须关闭自动重定向、重试，并且不要安装面向该公开端点的 Cookie 或认证插件。
SDK 不持久缓存、定时检测或收集遥测。完整下载 URL 可能含签名，宿主日志应避免输出。

SDK 不下载文件，不验证浏览器页面、文件完整性或安装结果。Android 交给 ACTION_VIEW；
桌面优先 Desktop.browse，Linux / macOS 明确失败时回退到 xdg-open / open。
命令使用参数数组且有超时，默认 URL 关联可能启动浏览器或已注册的链接处理应用。

## 构建、示例与验证

```bash
./gradlew :updater:jvmTest :updater:testAndroidHostTest \
  :updater-compose:jvmTest :updater-compose:testAndroidHostTest
./gradlew :sample-android:assembleDebug :sample-desktop:createDistributable
./gradlew :updater:publishToMavenLocal :updater-compose:publishToMavenLocal
# 真正通过 Maven 坐标消费两个库；仅此模式启用 mavenLocal()。
./gradlew -PusePublishedUpdater=true :sample-android:assembleDebug :sample-desktop:classes
```

配置 Android SDK：设置 ANDROID_HOME 或忽略的 local.properties 中的 sdk.dir。
Windows 使用 gradlew.bat。Linux 打包产物在
`sample-desktop/build/compose/binaries/main/app/KmpUpdaterSample/bin/KmpUpdaterSample`。

本地真实服务联调需要相邻 `updater-server` 仓库：

```bash
# 单独终端：临时数据库，仅监听 127.0.0.1:18082；不修改生产发布记录。
./scripts/run-server-fixture.sh /path/to/updater-server
# 另一终端：已连接 Android 设备，打开屏幕，允许系统首次测试 / 浏览器启动确认。
adb reverse tcp:18082 tcp:18082
./gradlew :sample-android:connectedDebugAndroidTest
# Linux 示例：应用配置 → 检查更新 → 前往下载
UPDATER_SAMPLE_URL=http://127.0.0.1:18082 ./gradlew :sample-desktop:run
# Linux 实际 xdg-open 回退路径验证：需要 DISPLAY 与默认浏览器。
./gradlew :sample-desktop:verifyLinuxBrowserFallback
```

fixture 为 sdk-demo 发布 versionCode=10，Linux/X86_64/appimage、Android/ARM64/apk。
本地 1 可见更新，10 相等，11 高于服务；zip 无匹配；sdk-missing 不存在。
下载地址是可观察 URL 参数的测试页面，不下载 / 安装文件。
fixture 用 Gradle init 脚本添加临时构建源，不修改服务端源码，停止后留下可删除的临时数据库。

[初版 Spec](docs/specs/v0.1-browser-update.html)。验证报告、截图和测试汇总仅本地保留于
`docs/verification/`，由 Git 忽略；复现命令与测试源码正常入库。

## 来源与许可证

基于 [Kotlin 官方库模板](https://github.com/Kotlin/multiplatform-library-template)
的 `226096493bde3c37595a968759ae2cf2c7618139` 创建。
本项目新增代码采用 [MIT License](LICENSE)。模板保留部分的原始 Apache-2.0
许可与来源见 [NOTICE](NOTICE)、[UPSTREAM-LICENSE.txt](UPSTREAM-LICENSE.txt)。
