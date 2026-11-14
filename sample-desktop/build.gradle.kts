import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}
dependencies {
    implementation(project(":sample-shared"))
    if (providers.gradleProperty("usePublishedUpdater").orNull == "true") {
        implementation("io.github.zerofancy:kmp-updater:0.2.1")
    } else implementation(project(":updater"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}
compose.desktop.application {
    mainClass = "top.ntutn.updater.sample.MainKt"
    nativeDistributions {
        targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
        packageName = "KmpUpdaterSample"
        packageVersion = "0.2.1"
        modules("java.net.http", "jdk.crypto.ec", "jdk.unsupported")
    }
}

// Explicit integration check; requires Linux, a running fixture, a graphical browser and DISPLAY.
tasks.register<JavaExec>("verifyLinuxBrowserFallback") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "top.ntutn.updater.sample.BrowserFallbackSmokeKt"
    systemProperty("java.awt.headless", "true")
}

// Real streaming HTTP and filesystem integration against the local fixture on port 18082.
tasks.register<JavaExec>("verifyInAppDownload") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "top.ntutn.updater.sample.DownloadSmokeKt"
}
