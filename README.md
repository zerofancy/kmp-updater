# KMP Updater

用于跨平台应用更新库的 Kotlin Multiplatform 基础项目。

当前只保留 **Android** 与 **JVM** 两个 target。Windows、Linux 桌面客户端复用 JVM 产物，
后续通过不同的安装策略适配各系统；没有 Kotlin/Native、iOS 或 Web target。

## 当前范围

- 独立 `updater` 库模块，启用显式公共 API。
- Ktor Client Core / OkHttp、Kotlinx Serialization、Coroutines 依赖。
- 公共数值版本模型 `UpdateVersion` 及其测试；版本名只用于展示。
- Android 宿主测试、JVM 测试及本地 Maven 发布。
- GitHub Actions 配置 Linux / Windows 构建验证。

这是建库基础，**尚未实现更新查询、文件下载、系统安装或示例应用**。
服务器地址、软件内部名和安装策略将在实现时由宿主配置，不绑定任何部署服务。

## 目录

```text
updater/src/
├── commonMain/       # 公共 API、协议、下载逻辑
├── commonTest/       # 可在 Android/JVM 执行的公共测试
├── androidMain/      # 后续 Android 文件存储与安装实现
└── jvmMain/          # 后续 Windows/Linux 平台适配
```

## 本机构建

需要 JDK 21 和 Android SDK，compileSdk 37、minSdk 24。
使用环境变量 `ANDROID_HOME`，或在忽略的 `local.properties` 中配置 `sdk.dir`。
工具链采用 Gradle 9.7.1、AGP 9.5.0、Kotlin 2.4.20。
依赖版本统一在 `gradle/libs.versions.toml` 中管理。

```bash
./gradlew :updater:jvmTest :updater:testAndroidHostTest :updater:assemble
./gradlew :updater:publishToMavenLocal
```

Windows 可使用 `gradlew.bat`。宿主测试不代表 Android 真机安装测试。

## 本地使用

在消费项目临时启用 `mavenLocal()`，然后引用公共坐标：

```kotlin
commonMain.dependencies {
    implementation("io.github.zerofancy:kmp-updater:0.1.0-SNAPSHOT")
}
```

KMP 元数据会选择 Android 或 JVM 平台产物。当前未发布到 Maven Central，
也未配置自动远程发布、发布令牌或签名密钥；GitHub 仓库发布与 Maven 发布相互独立。

## 来源与许可证

基于 [Kotlin 官方库模板](https://github.com/Kotlin/multiplatform-library-template)
的 `226096493bde3c37595a968759ae2cf2c7618139` 创建。
本项目新增代码采用 [MIT License](LICENSE)。官方模板保留部分的原始 Apache-2.0
许可及来源说明见 [NOTICE](NOTICE) 与 [UPSTREAM-LICENSE.txt](UPSTREAM-LICENSE.txt)。
