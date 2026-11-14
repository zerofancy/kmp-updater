package top.ntutn.update.fixture

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import top.ntutn.update.configure
import top.ntutn.update.config.Settings
import top.ntutn.update.database.DatabaseStore
import top.ntutn.update.model.*
import top.ntutn.update.repository.PublishingRepository
import java.nio.file.Files

private const val origin = "http://127.0.0.1:18082"
private val payload = ByteArray(1024 * 1024) { (it % 251).toByte() }

/** Local binary fixtures only: files are deliberately not installable APKs / AppImages. */
fun main() {
    val directory = Files.createTempDirectory("kmp-updater-server-fixture-")
    val db = directory.resolve("fixture.db")
    runBlocking {
        DatabaseStore(db).use { store ->
            val repo = PublishingRepository(store)
            listOf(
                Triple("sdk-demo", "/download", null),
                Triple("sdk-known", "/download", payload.size.toLong()),
                Triple("sdk-slow", "/download/slow", null),
                Triple("sdk-html", "/download/html", null),
                Triple("sdk-failed", "/download/failed", null),
                Triple("sdk-redirect", "/download/redirect", payload.size.toLong()),
            ).forEach { (software, path, size) ->
                val id = repo.saveSoftware(Software(software, "SDK 联调示例"))
                val packages = OS.entries.map { os ->
                    val type = if (os == OS.Android) "apk" else "appimage"
                    SoftwarePackage("${os.name} binary fixture", Arch.entries.map { PlatformTarget(os, it) }.toSet(),
                        "$origin$path?platform=${os.name}&token=a%2Bb%26c", size, 10, "1.2.0", packageType = type)
                }
                repo.save(DistributionDraft(id, "SDK v0.2 联调", packages,
                    "## 本次更新\n- **应用内下载**与浏览器回退",
                    "本次更新\n• 完成跨平台更新检测\n• 应用内下载与浏览器回退验证\n纯文本 <literal> & token 保留"))
            }
        }
    }
    println("SDK_FIXTURE_DB=$db")
    embeddedServer(Netty, host = "127.0.0.1", port = 18082) {
        configure(Settings("fixture", "local-fixture-only-password", db, origin, false))
        routing {
            get("/download") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=fixture.bin")
                println("SDK_DOWNLOAD platform=${call.request.queryParameters["platform"]}")
                call.respondBytes(payload, ContentType.Application.OctetStream)
            }
            get("/download/slow") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondBytesWriter(ContentType.Application.OctetStream) {
                    repeat(64) {
                        writeFully(payload, 0, 64 * 1024)
                        flush()
                        delay(100)
                    }
                }
            }
            get("/download/html") {
                call.respondText("<!doctype html><meta charset='utf-8'><title>Download page</title><h1>此地址是下载页面</h1>", ContentType.Text.Html)
            }
            get("/download/failed") {
                call.respondText("temporarily unavailable", ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
            }
            get("/download/redirect") { call.respondRedirect("/download", permanent = false) }
            get("/browser") {
                call.respondText("<!doctype html><meta charset='utf-8'><title>SDK browser handoff verified</title><h1>SDK 浏览器交接验证成功</h1><p>这是本地联调页面，不执行安装。</p>", ContentType.Text.Html)
            }
        }
    }.start(wait = true)
}
