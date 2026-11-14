package top.ntutn.updater

import kotlinx.coroutines.CoroutineDispatcher

internal expect fun downloadIoDispatcher(): CoroutineDispatcher

internal interface DownloadFileStore {
    fun begin(release: UpdateRelease, expectedBytes: Long?): DownloadFileSession
    suspend fun delete(file: DownloadedFile)
}

internal interface DownloadFileSession {
    fun write(bytes: ByteArray, count: Int)
    fun finish(size: Long): DownloadedFile
    suspend fun abort()
}

internal class DownloadFailure(val error: DownloadError) : Exception()

internal fun failDownload(kind: DownloadErrorKind, status: Int? = null): Nothing =
    throw DownloadFailure(DownloadError(kind, status))
