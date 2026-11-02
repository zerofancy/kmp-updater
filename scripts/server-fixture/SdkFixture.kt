package top.ntutn.update.fixture

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import top.ntutn.update.configure
import top.ntutn.update.config.Settings
import top.ntutn.update.database.DatabaseStore
import top.ntutn.update.model.*
import top.ntutn.update.repository.PublishingRepository
import java.nio.file.Files

fun main() {
    val directory = Files.createTempDirectory("kmp-updater-server-fixture-")
    val db = directory.resolve("fixture.db")
    runBlocking {
        DatabaseStore(db).use { store ->
            val repo = PublishingRepository(store)
            val id = repo.saveSoftware(Software("sdk-demo", "SDK 联调示例"))
            repo.save(DistributionDraft(id, "SDK v0.1 联调", listOf(
                SoftwarePackage("Linux AppImage", setOf(PlatformTarget(OS.Linux, Arch.X86_64)),
                    "http://127.0.0.1:18082/download?platform=Linux&token=a%2Bb%26c", 67318579, 10, "1.2.0", packageType = "appimage"),
                SoftwarePackage("Android APK", setOf(PlatformTarget(OS.Android, Arch.ARM64)),
                    "http://127.0.0.1:18082/download?platform=Android&token=a%2Bb%26c", null, 10, "1.2.0", packageType = "apk")
            ), "## 本次更新\n- **更新检测**与浏览器跳转", "本次更新\n• 完成跨平台更新检测\n• 浏览器跳转验证\n纯文本 <literal> & token 保留"))
        }
    }
    println("SDK_FIXTURE_DB=$db")
    embeddedServer(Netty, host = "127.0.0.1", port = 18082) {
        configure(Settings("fixture", "local-fixture-only-password", db, "http://127.0.0.1:18082", false))
        routing {
            get("/download") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                println("SDK_BROWSER_VISIT platform=${call.request.queryParameters["platform"]} token=${call.request.queryParameters["token"]}")
                call.respondText("<!doctype html><meta charset='utf-8'><title>SDK browser handoff verified</title><h1>SDK 浏览器交接验证成功</h1><p>Platform: ${call.request.queryParameters["platform"]}</p><p>Token preserved: ${call.request.queryParameters["token"]}</p><p>这是本地联调页面，不执行下载或安装。</p>", ContentType.Text.Html)
            }
        }
    }.start(wait = true)
}
