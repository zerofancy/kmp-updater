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
import kotlin.time.Duration.Companion.hours

internal actual fun downloadIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

/** All mutation is restricted to marked, uniquely named task directories directly beneath root. */
internal class JvmDownloadFileStore(
    private val directory: File,
    private val options: DownloadOptions,
    private val allowApkHandoff: Boolean = false,
) : DownloadFileStore {
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
            lock.use {
                if (isProtected(file.task, System.currentTimeMillis())) failDownload(DownloadErrorKind.Busy)
                cleanKnownFiles(file.task, file.fileName, strict = true)
            }
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
                    val now = System.currentTimeMillis()
                    val expired = now - metadata.second > options.completedFileRetention.inWholeMilliseconds
                    if (metadata.second == 0L || (expired && !isProtected(task, now))) {
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

    private fun isOwnedTask(task: File): Boolean = isOwnedTaskDirectory(task) && readMetadata(task) != null

    private fun isOwnedTaskDirectory(task: File): Boolean = task.isDirectory && TASK_NAME.matches(task.name) &&
        task.parentFile == root && task.absoluteFile == task.canonicalFile

    private fun readMetadata(task: File): Pair<String, Long>? = runCatching {
        val marker = File(task, MARKER)
        if (marker.absoluteFile != marker.canonicalFile || !marker.isFile || marker.length() > 512) return@runCatching null
        val lines = marker.readLines()
        if (lines.size != 3 || lines[0] != MAGIC || !SAFE_NAME.matches(lines[1])) return@runCatching null
        val completed = lines[2].toLongOrNull()?.takeIf { it >= 0 } ?: return@runCatching null
        lines[1] to completed
    }.getOrNull()

    private fun isProtected(task: File, now: Long): Boolean = allowApkHandoff && installProtectionUntil(task) > now

    private fun cleanKnownFiles(task: File, fileName: String, strict: Boolean = false): Boolean {
        var removedPayloads = true
        val names = if (allowApkHandoff) listOf(PART, fileName, INSTALL_PROTECTION, INSTALL_PROTECTION_TEMPORARY)
            else listOf(PART, fileName)
        for (name in names) {
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

    private class TaskLock(private val path: File, private val access: RandomAccessFile, private val lock: FileLock) : AutoCloseable {
        private var closed = false

        override fun close(): Unit = synchronized(LOCKED_PATHS) {
            if (closed) return@synchronized
            closed = true
            runCatching { lock.release() }
            runCatching { access.close() }
            LOCKED_PATHS.remove(path)
        }
    }

    private fun lockTask(task: File): TaskLock? = synchronized(LOCKED_PATHS) {
        val file = File(task, LOCK)
        if (file.absoluteFile != file.canonicalFile) throw IOException("Invalid task lock")
        // Closing a competing descriptor can release this process's fcntl lock on the same file.
        if (file in LOCKED_PATHS) return@synchronized null
        val access = RandomAccessFile(file, "rw")
        try {
            val lock = access.channel.tryLock()
            if (lock != null) {
                LOCKED_PATHS.add(file)
                return@synchronized TaskLock(file, access, lock)
            }
        } catch (_: OverlappingFileLockException) {
            // Another downloader in this process owns the task.
        } catch (error: Exception) {
            access.close()
            throw error
        }
        access.close()
        return null
    }

    companion object {
        private const val MAGIC = "kmp-updater-download-v1"
        private const val ROOT_MARKER = ".kmp-updater-root"
        private const val MARKER = ".task"
        private const val LOCK = ".lock"
        private const val PART = "payload.part"
        private val TASK_NAME = Regex("task-[0-9a-f-]{36}")
        private val SAFE_NAME = Regex("[a-zA-Z0-9._-]{1,220}")
        private val INIT_MONITOR = Any()
        private val LOCKED_PATHS = mutableSetOf<File>()

        /** The task stays locked through parsing and the Activity launch. */
        suspend fun <T> withCompletedApk(
            directory: File,
            file: DownloadedFile,
            block: suspend (ApkHandoff) -> T,
        ): T = withContext(Dispatchers.IO) {
            val stored = file as? StoredFile ?: throw ApkHandoffFailure(ApkHandoffError.Unsupported)
            val store = stored.owner
            val expectedRoot = File(directory.parentFile?.canonicalFile, directory.name)
            if (!store.allowApkHandoff || store.root != expectedRoot || expectedRoot != expectedRoot.canonicalFile ||
                !stored.fileName.endsWith(".apk")
            ) {
                throw ApkHandoffFailure(ApkHandoffError.Unsupported)
            }
            val task = stored.task
            if (!task.exists()) throw ApkHandoffFailure(ApkHandoffError.Unavailable)
            if (!store.isOwnedTaskDirectory(task)) throw ApkHandoffFailure(ApkHandoffError.Unsupported)
            val lock = store.lockTask(task) ?: throw ApkHandoffFailure(ApkHandoffError.Busy)
            lock.use {
                val metadata = store.readMetadata(task) ?: throw ApkHandoffFailure(ApkHandoffError.Unavailable)
                val apk = File(stored.localPath)
                if (metadata.second == 0L || metadata.first != apk.name || apk.parentFile != task || apk != apk.canonicalFile) {
                    throw ApkHandoffFailure(ApkHandoffError.Unsupported)
                }
                if (!apk.isFile || !apk.canRead() || stored.sizeBytes <= 0 || apk.length() != stored.sizeBytes) {
                    throw ApkHandoffFailure(ApkHandoffError.Unavailable)
                }
                block(ApkHandoff(apk, task))
            }
        }
    }
}

internal enum class ApkHandoffError { Unsupported, Unavailable, Busy }
internal class ApkHandoffFailure(val reason: ApkHandoffError) : Exception()

/** Called on IO while the completed download's task lock is held. */
internal class ApkHandoff(val apk: File, private val task: File) {
    fun protect(): Long {
        val previous = installProtectionUntil(task)
        setProtectionUntil(maxOf(previous, System.currentTimeMillis() + 24.hours.inWholeMilliseconds))
        return previous
    }

    fun setProtectionUntil(until: Long) {
        val marker = File(task, INSTALL_PROTECTION)
        if (until == 0L) {
            if (!marker.delete() && marker.exists()) throw IOException("Cannot clear install protection")
            return
        }
        val temporary = File(task, INSTALL_PROTECTION_TEMPORARY)
        // A process can stop between writing the replacement and publishing it.
        temporary.delete()
        if (!temporary.createNewFile()) throw IOException("Cannot prepare install protection")
        try {
            FileOutputStream(temporary).use {
                it.write(until.toString().encodeToByteArray())
                it.fd.sync()
            }
            if (!temporary.renameTo(marker)) throw IOException("Cannot publish install protection")
        } finally {
            temporary.delete()
        }
    }
}

private const val INSTALL_PROTECTION = ".install-until"
private const val INSTALL_PROTECTION_TEMPORARY = ".install-until.part"

private fun installProtectionUntil(task: File): Long {
    val marker = File(task, INSTALL_PROTECTION)
    if (!marker.exists()) return 0L
    if (marker.absoluteFile == marker.canonicalFile && marker.isFile && marker.length() in 1..19) {
        marker.readText().toLongOrNull()?.takeIf { it > 0 }?.let { return it }
    }
    throw IOException("Invalid install protection")
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
