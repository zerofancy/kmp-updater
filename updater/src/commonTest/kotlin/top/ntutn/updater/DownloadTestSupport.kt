package top.ntutn.updater

import kotlinx.coroutines.CompletableDeferred

internal fun downloadRelease(url: String = "https://files.example/app", size: Long? = null): UpdateRelease = UpdateRelease(
    software = "demo", target = UpdateTarget(UpdateOs.Linux, UpdateArch.X86_64), packageType = "appimage",
    version = UpdateVersion(10, "1.0"), downloadUrl = url, softwareDisplayName = "Demo", distributionTitle = "",
    packageName = "../../unsafe.AppImage", distributionId = null, packageId = null, publishedAt = null,
    packageSize = size, descriptionPlainText = null, descriptionMarkdown = null,
)

internal class MemoryDownloadStore : DownloadFileStore {
    var begins = 0
    var writes = 0
    var maxChunk = 0
    var written = 0L
    var aborted = false
    var completed = false
    val started = CompletableDeferred<Unit>()
    val abortStarted = CompletableDeferred<Unit>()
    var abortGate: CompletableDeferred<Unit>? = null

    override fun begin(release: UpdateRelease, expectedBytes: Long?): DownloadFileSession {
        begins++
        started.complete(Unit)
        return object : DownloadFileSession {
            override fun write(bytes: ByteArray, count: Int) { writes++; maxChunk = maxOf(maxChunk, count); written += count }
            override fun finish(size: Long): DownloadedFile {
                completed = true
                return object : DownloadedFile {
                    override val localPath = "/test/completed.bin"
                    override val fileName = "completed.bin"
                    override val sizeBytes = size
                    override suspend fun exists() = completed
                }
            }
            override suspend fun abort() {
                abortStarted.complete(Unit)
                abortGate?.await()
                aborted = true
                completed = false
            }
        }
    }
    override suspend fun delete(file: DownloadedFile) { completed = false }
}
