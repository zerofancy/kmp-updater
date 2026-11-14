package top.ntutn.updater

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

internal actual fun downloadIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

/** All mutation is restricted to marked, uniquely named task directories directly beneath root. */
internal class JvmDownloadFileStore(private val directory: File, private val options: DownloadOptions) : DownloadFileStore {
    private var initialized = false
    private lateinit var root: File

    override fun begin(release: UpdateRelease, expectedBytes: Long?): DownloadFileSession = fileOperation {
        initialize()
        if (expectedBytes != null && root.usableSpace in 1 until expectedBytes) failDownload(DownloadErrorKind.InsufficientSpace)
        val task = File(root, "task-${UUID.randomUUID()}")
        if (!task.mkdir()) throw IOException("Cannot create download directory")
        val fileName = downloadFileName(release)
        var lock: TaskLock? = null
        try {
            lock = lockTask(task) ?: throw IOException("Cannot lock new task")
            File(task, MARKER).writeText("$MAGIC\n$fileName\n0")
            Session(task, fileName, lock, FileOutputStream(File(task, PART)))
        } catch (error: Exception) {
            lock?.close()
            if (runCatching { cleanKnownFiles(task, fileName) }.getOrDefault(false)) {
                File(task, LOCK).delete()
                task.delete()
            }
            throw error
        }
    }

    override suspend fun delete(file: DownloadedFile): Unit = withContext(Dispatchers.IO) {
        require(file is StoredFile && file.owner === this@JvmDownloadFileStore) { "File was not created by this downloader" }
        fileOperation {
            initialize()
            if (!file.task.exists()) return@fileOperation
            if (!isOwnedTask(file.task)) throw IOException("Invalid download directory")
            val lock = lockTask(file.task) ?: failDownload(DownloadErrorKind.Busy)
            lock.use { cleanKnownFiles(file.task, file.fileName, strict = true) }
            File(file.task, LOCK).delete()
            file.task.delete()
        }
    }

    @Synchronized
    private fun initialize() {
        if (initialized) return
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create updater root")
        root = directory.canonicalFile
        // Serialize first-time marker publication across both instances and processes.
        synchronized(INIT_MONITOR) {
            RandomAccessFile(File(root, ".root.lock"), "rw").use { access ->
                access.channel.lock().use {
                    val rootMarker = File(root, ROOT_MARKER)
                    if (rootMarker.exists() && rootMarker.length() > 0 && rootMarker.readText() != MAGIC) {
                        throw IOException("Unrecognized updater root")
                    }
                    rootMarker.writeText(MAGIC)
                }
            }
        }
        root.listFiles()?.forEach { task ->
            if (!isOwnedTask(task)) return@forEach
            runCatching {
                val lock = lockTask(task) ?: return@runCatching
                lock.use {
                    val metadata = readMetadata(task) ?: return@use
                    val age = System.currentTimeMillis() - metadata.second
                    if (metadata.second == 0L || age > options.completedFileRetention.inWholeMilliseconds) {
                        cleanKnownFiles(task, metadata.first)
                    }
                }
                if (task.listFiles()?.all { it.name == LOCK } == true) {
                    File(task, LOCK).delete()
                    task.delete()
                }
            }
        }
        initialized = true
    }

    private fun isOwnedTask(task: File): Boolean = task.isDirectory && TASK_NAME.matches(task.name) &&
        task.absoluteFile == task.canonicalFile && task.canonicalFile.parentFile == root && readMetadata(task) != null

    private fun readMetadata(task: File): Pair<String, Long>? = runCatching {
        val marker = File(task, MARKER)
        if (marker.length() > 512 || marker.canonicalFile.parentFile != task.canonicalFile) return@runCatching null
        val lines = marker.readLines()
        if (lines.size != 3 || lines[0] != MAGIC || !SAFE_NAME.matches(lines[1])) return@runCatching null
        val completed = lines[2].toLongOrNull()?.takeIf { it >= 0 } ?: return@runCatching null
        lines[1] to completed
    }.getOrNull()

    private fun cleanKnownFiles(task: File, fileName: String, strict: Boolean = false): Boolean {
        var removedPayloads = true
        for (name in listOf(PART, fileName)) {
            val file = File(task, name)
            if (file.canonicalFile.parentFile != task.canonicalFile || file.isDirectory) {
                if (strict) throw IOException("Invalid download file")
                removedPayloads = false
                continue
            }
            if (file.exists() && !file.delete()) {
                if (strict) throw IOException("Cannot delete download file")
                removedPayloads = false
            }
        }
        // Failed removal must retain its marker so the next initialization can retry.
        if (!removedPayloads) return false
        val marker = File(task, MARKER)
        if (marker.exists() && !marker.delete()) {
            if (strict) throw IOException("Cannot delete download marker")
            return false
        }
        return true
    }

    private inner class Session(
        private val task: File,
        private val fileName: String,
        private val lock: TaskLock,
        private val output: FileOutputStream,
    ) : DownloadFileSession {
        override fun write(bytes: ByteArray, count: Int): Unit = fileOperation { output.write(bytes, 0, count) }

        override fun finish(size: Long): DownloadedFile = fileOperation {
            output.close()
            val complete = File(task, fileName)
            if (!File(task, PART).renameTo(complete)) throw IOException("Cannot finalize download")
            File(task, MARKER).writeText("$MAGIC\n$fileName\n${System.currentTimeMillis()}")
            lock.close()
            StoredFile(this@JvmDownloadFileStore, task, complete, size)
        }

        override suspend fun abort() {
            runCatching { output.close() }
            val cleaned = runCatching { cleanKnownFiles(task, fileName) }.getOrDefault(false)
            lock.close()
            if (cleaned) {
                File(task, LOCK).delete()
                task.delete()
            }
        }
    }

    private class StoredFile(
        val owner: JvmDownloadFileStore,
        val task: File,
        private val file: File,
        override val sizeBytes: Long,
    ) : DownloadedFile {
        override val localPath: String get() = file.absolutePath
        override val fileName: String get() = file.name
        override suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
            file.isFile && file.length() == sizeBytes && file.canonicalFile.parentFile == task.canonicalFile
        }
    }

    private class TaskLock(private val access: RandomAccessFile, private val lock: FileLock) : AutoCloseable {
        override fun close() { runCatching { lock.release() }; runCatching { access.close() } }
    }

    private fun lockTask(task: File): TaskLock? {
        val file = File(task, LOCK)
        if (file.canonicalFile.parentFile != task.canonicalFile) throw IOException("Invalid task lock")
        val access = RandomAccessFile(file, "rw")
        try {
            val lock = access.channel.tryLock()
            if (lock != null) return TaskLock(access, lock)
        } catch (_: OverlappingFileLockException) {
            // Another downloader in this process owns the task.
        } catch (error: Exception) {
            access.close()
            throw error
        }
        access.close()
        return null
    }

    private companion object {
        const val MAGIC = "kmp-updater-download-v1"
        const val ROOT_MARKER = ".kmp-updater-root"
        const val MARKER = ".task"
        const val LOCK = ".lock"
        const val PART = "payload.part"
        val TASK_NAME = Regex("task-[0-9a-f-]{36}")
        val SAFE_NAME = Regex("[a-zA-Z0-9._-]{1,220}")
        val INIT_MONITOR = Any()
    }
}

private inline fun <T> fileOperation(block: () -> T): T = try { block() }
catch (failure: DownloadFailure) { throw failure }
catch (_: IOException) { failDownload(DownloadErrorKind.FileIo) }
catch (_: SecurityException) { failDownload(DownloadErrorKind.FileIo) }

internal fun downloadFileName(release: UpdateRelease): String {
    val software = release.software.takeIf { it.matches(Regex("[a-z0-9][a-z0-9._-]{0,99}")) } ?: "update"
    val extension = when (release.packageType) {
        "appimage" -> "AppImage"
        "apk", "dmg", "msi", "exe", "deb", "rpm", "zip", "pkg", "tar", "gz" -> release.packageType
        else -> "bin"
    }
    return "$software-${release.version.code}-${release.target.os.name.lowercase()}-${release.target.arch.name.lowercase()}.$extension"
}
