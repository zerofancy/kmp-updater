package top.ntutn.updater

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking

class ApkHandoffStorageTest {
    @Test fun retainsHandedOffFileAcrossCleanupAndRejectsConcurrentDelete() = runBlocking {
        val directory = Files.createTempDirectory("updater-install-retention-").toFile()
        val options = DownloadOptions(completedFileRetention = 1.milliseconds)
        try {
            val store = JvmDownloadFileStore(directory, options, allowApkHandoff = true)
            val file = store.completeApk()
            val task = File(file.localPath).parentFile
            // Seed an old completion time without waiting for the configured retention to expire.
            File(task, ".task").writeText("kmp-updater-download-v1\n${file.fileName}\n1")

            JvmDownloadFileStore.withCompletedApk(directory, file) { handoff ->
                val busy = assertFailsWith<ApkHandoffFailure> {
                    JvmDownloadFileStore.withCompletedApk(directory, file) { error("Lock should be held") }
                }
                assertEquals(ApkHandoffError.Busy, busy.reason)
                assertEquals(DownloadErrorKind.Busy, assertFailsWith<DownloadFailure> { store.delete(file) }.error.kind)
                assertEquals(0L, handoff.protect())
            }

            val restarted = JvmDownloadFileStore(directory, options, allowApkHandoff = true)
            restarted.begin(downloadRelease(), null).abort()
            assertTrue(file.exists())
            assertEquals(DownloadErrorKind.Busy, assertFailsWith<DownloadFailure> { store.delete(file) }.error.kind)

            File(task, ".install-until").writeText("1")
            JvmDownloadFileStore(directory, options, allowApkHandoff = true).begin(downloadRelease(), null).abort()
            assertFalse(task.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun rejectsForeignHandlesAndModifiedDownloads() = runBlocking {
        val directory = Files.createTempDirectory("updater-install-files-").toFile()
        try {
            val store = JvmDownloadFileStore(directory, DownloadOptions(), allowApkHandoff = true)
            val file = store.completeApk()
            val forged = object : DownloadedFile by file {}
            assertEquals(ApkHandoffError.Unsupported, handoffError(directory, forged))
            assertEquals(ApkHandoffError.Unsupported, handoffError(File(directory, "elsewhere"), file))

            val apk = File(file.localPath)
            apk.appendBytes(byteArrayOf(2))
            assertEquals(ApkHandoffError.Unavailable, handoffError(directory, file))
            assertTrue(apk.delete())
            assertEquals(ApkHandoffError.Unavailable, handoffError(directory, file))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun JvmDownloadFileStore.completeApk(): DownloadedFile {
        val release = downloadRelease().copy(packageType = "apk", target = UpdateTarget(UpdateOs.Android, UpdateArch.ARM64))
        val session = begin(release, expectedBytes = 1)
        session.write(byteArrayOf(1), 1)
        return session.finish(1)
    }

    private suspend fun handoffError(directory: File, file: DownloadedFile): ApkHandoffError =
        assertFailsWith<ApkHandoffFailure> {
            JvmDownloadFileStore.withCompletedApk(directory, file) { error("File should have been rejected") }
        }.reason
}
