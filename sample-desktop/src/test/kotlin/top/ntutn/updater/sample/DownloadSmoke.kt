package top.ntutn.updater.sample

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import top.ntutn.updater.*

/** Real HTTP and filesystem verification against scripts/run-server-fixture.sh. */
fun main() = runBlocking {
    val directory = Files.createTempDirectory("kmp-updater-download-smoke-").toFile()
    val downloader = createDesktopUpdateDownloader(directory, DownloadOptions(allowInsecureHttp = true))
    suspend fun release(software: String): UpdateRelease {
        val checker = createUpdateChecker(UpdaterConfig(
            "http://127.0.0.1:18082", software, UpdateVersion(1, "1"),
            UpdateTarget(UpdateOs.Linux, UpdateArch.X86_64), "appimage", allowInsecureHttp = true,
        ))
        return try {
            val result = checker.check()
            check(result is UpdateCheckResult.Available) { "Fixture did not offer $software: $result" }
            result.release
        } finally { checker.close() }
    }
    try {
        val progress = mutableListOf<DownloadProgress>()
        val known = downloader.download(release("sdk-known"), progress::add)
        check(known is DownloadResult.Completed) { "Known-size download failed: $known" }
        check(known.file.exists())
        check(known.file.sizeBytes == 1024L * 1024)
        File(known.file.localPath).inputStream().use { input ->
            var count = 0
            while (true) {
                val value = input.read()
                if (value == -1) break
                check(value == count % 251) { "Incorrect payload byte at $count" }
                count++
            }
            check(count == 1024 * 1024)
        }
        check(progress.any { it.totalBytes == 1024L * 1024 })
        println("Known size: 1 MiB, deterministic payload and completed handle verified")

        val html = downloader.download(release("sdk-html"))
        check(html is DownloadResult.Failed && html.error.kind == DownloadErrorKind.UnsupportedContent)
        println("HTML download page: rejected with UnsupportedContent")

        val redirect = downloader.download(release("sdk-redirect"))
        check(redirect is DownloadResult.Completed && redirect.file.sizeBytes == 1024L * 1024)
        println("Relative redirect: downloaded successfully")

        val slow = release("sdk-slow")
        val started = CompletableDeferred<Unit>()
        val cancelled = launch {
            downloader.download(slow) { if (it.downloadedBytes > 0) started.complete(Unit) }
        }
        withTimeout(10000) { started.await() }
        cancelled.cancelAndJoin()
        downloader.awaitIdle()
        check(directory.walkTopDown().none { it.name.endsWith(".part") })
        println("Cancellation: request stopped and temporary file removed")

        val unknown = mutableListOf<DownloadProgress>()
        val retried = downloader.download(slow, unknown::add)
        check(retried is DownloadResult.Completed && retried.file.sizeBytes == 4L * 1024 * 1024)
        check(unknown.any { it.downloadedBytes > 0 && it.totalBytes == null })
        check(directory.walkTopDown().none { it.name.endsWith(".part") })
        println("Retry after cancellation: 4 MiB chunked file, unknown total size verified")

        downloader.delete(known.file)
        check(!known.file.exists())
        println("Owned completed file: explicit deletion verified")
    } finally {
        downloader.close()
        downloader.close()
        downloader.awaitIdle()
        directory.deleteRecursively()
    }
}
