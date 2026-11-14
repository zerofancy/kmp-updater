package top.ntutn.updater

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class DownloadStorageTest {
    @Test fun downloadsRealFileUsesSafeNamesAndRestrictsDeleteOwnership() = runBlocking {
        val root = Files.createTempDirectory("updater-download-").toFile()
        val bytes = ByteArray(250_000) { (it % 251).toByte() }
        val client = HttpClient(MockEngine { respond(bytes, headers = headersOf("Content-Length", bytes.size.toString())) }) { followRedirects = false }
        val first = createDesktopUpdateDownloader(root, client = client)
        val other = createDesktopUpdateDownloader(root, client = client)
        try {
            val file = assertIs<DownloadResult.Completed>(first.download(downloadRelease(size = bytes.size.toLong()))).file
            assertEquals("demo-10-linux-x86_64.AppImage", file.fileName)
            assertTrue(file.exists())
            assertContentEquals(bytes, File(file.localPath).readBytes())
            assertTrue(File(file.localPath).canonicalPath.startsWith(File(root, "kmp-updater").canonicalPath + File.separator))
            assertFailsWith<IllegalArgumentException> { other.delete(file) }
            val forged = object : DownloadedFile {
                override val localPath = file.localPath
                override val fileName = file.fileName
                override val sizeBytes = file.sizeBytes
                override suspend fun exists() = true
            }
            assertFailsWith<IllegalArgumentException> { first.delete(forged) }
            assertTrue(file.exists())
            first.delete(file)
            assertFalse(file.exists())
            first.delete(file)
        } finally { first.close(); other.close(); client.close(); root.deleteRecursively() }
    }

    @Test fun cancellationRemovesPartialPayloadAndTaskDirectory() = runBlocking {
        val root = Files.createTempDirectory("updater-cancel-").toFile()
        val client = HttpClient(MockEngine { respond(ByteChannel()) }) { followRedirects = false }
        val downloader = createDesktopUpdateDownloader(root, client = client)
        val started = CompletableDeferred<Unit>()
        try {
            val call = async { downloader.download(downloadRelease()) { started.complete(Unit) } }
            started.await()
            assertTrue(root.walkTopDown().any { it.name == "payload.part" })
            call.cancelAndJoin()
            downloader.awaitIdle()
            assertFalse(root.walkTopDown().any { it.name.startsWith("task-") })
        } finally { downloader.close(); client.close(); root.deleteRecursively() }
    }

    @Test fun secondInstanceDoesNotCleanLockedActiveTask() = runBlocking {
        val directory = Files.createTempDirectory("updater-lock-").toFile()
        val first = JvmDownloadFileStore(directory, DownloadOptions())
        val second = JvmDownloadFileStore(directory, DownloadOptions())
        try {
            val active = withContext(Dispatchers.IO) { first.begin(downloadRelease(), null) }
            active.write(byteArrayOf(1), 1)
            val next = withContext(Dispatchers.IO) { second.begin(downloadRelease(), null) }
            assertEquals(2, directory.listFiles()!!.count { it.isDirectory })
            active.write(byteArrayOf(2, 3), 2)
            val completed = active.finish(3)
            assertContentEquals(byteArrayOf(1, 2, 3), File(completed.localPath).readBytes())
            next.abort()
            first.delete(completed)
        } finally { directory.deleteRecursively() }
    }

    @Test fun concurrentFirstInitializationPublishesRootMarkerSafely() = runBlocking {
        val parent = Files.createTempDirectory("updater-init-").toFile()
        val directory = File(parent, "not-created-yet")
        try {
            val completed = coroutineScope {
                (1..12).map {
                    async(Dispatchers.IO) {
                        val store = JvmDownloadFileStore(directory, DownloadOptions())
                        val session = store.begin(downloadRelease(), null)
                        session.write(byteArrayOf(1), 1)
                        val file = session.finish(1)
                        assertTrue(file.exists())
                        store to file
                    }
                }.awaitAll()
            }
            completed.forEach { (store, file) -> store.delete(file) }
            assertEquals("kmp-updater-download-v1", File(directory, ".kmp-updater-root").readText())
        } finally { parent.deleteRecursively() }
    }

    @Test fun cleansAbandonedAndExpiredTasksButPreservesUnknownFilesAndFreshResults() = runBlocking {
        val directory = Files.createTempDirectory("updater-stale-").toFile()
        fun task(completed: Long, unknown: Boolean = false): File {
            val task = File(directory, "task-${UUID.randomUUID()}").apply { mkdir() }
            File(task, ".task").writeText("kmp-updater-download-v1\npayload.bin\n$completed")
            File(task, if (completed == 0L) "payload.part" else "payload.bin").writeText("old")
            if (unknown) File(task, "user.txt").writeText("keep")
            return task
        }
        try {
            val abandoned = task(0)
            val expired = task(1)
            val fresh = task(System.currentTimeMillis())
            val unknown = task(0, unknown = true)
            val unmarked = File(directory, "task-${UUID.randomUUID()}").apply { mkdir() }
            File(unmarked, "payload.part").writeText("user content")
            val store = JvmDownloadFileStore(directory, DownloadOptions())
            val session = withContext(Dispatchers.IO) { store.begin(downloadRelease(), null) }
            assertFalse(abandoned.exists())
            assertFalse(expired.exists())
            assertTrue(File(fresh, "payload.bin").isFile)
            assertEquals("keep", File(unknown, "user.txt").readText())
            assertEquals("user content", File(unmarked, "payload.part").readText())
            session.abort()
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedCleanupRetainsMarkerForNextInitialization() = runBlocking {
        val directory = Files.createTempDirectory("updater-retain-").toFile()
        try {
            val store = JvmDownloadFileStore(directory, DownloadOptions())
            val session = withContext(Dispatchers.IO) { store.begin(downloadRelease(), null) }
            val task = directory.listFiles()!!.single { it.isDirectory }
            // A directory in place of the final file models a payload that cannot safely be removed.
            val payload = File(task, downloadFileName(downloadRelease())).apply { mkdir() }
            File(payload, "foreign.txt").writeText("do not remove")
            session.abort()
            assertTrue(File(task, ".task").isFile)
            assertEquals("do not remove", File(payload, "foreign.txt").readText())
            payload.deleteRecursively()
            val next = JvmDownloadFileStore(directory, DownloadOptions())
            val nextSession = withContext(Dispatchers.IO) { next.begin(downloadRelease(), null) }
            assertFalse(task.exists())
            nextSession.abort()
        } finally { directory.deleteRecursively() }
    }

    @Test fun inaccessibleTargetFailsAsFileIoAndDoesNotModifyForeignFile() = runBlocking {
        val directory = Files.createTempDirectory("updater-unwritable-").toFile()
        val target = File(directory, "kmp-updater").apply { writeText("foreign") }
        val client = HttpClient(MockEngine { respond(byteArrayOf(1)) }) { followRedirects = false }
        val downloader = createDesktopUpdateDownloader(directory, client = client)
        try {
            assertEquals(DownloadErrorKind.FileIo, assertIs<DownloadResult.Failed>(downloader.download(downloadRelease())).error.kind)
            assertEquals("foreign", target.readText())
        } finally { downloader.close(); client.close(); directory.deleteRecursively() }
    }
}
