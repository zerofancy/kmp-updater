package top.ntutn.updater

import java.security.MessageDigest

internal actual fun createDownloadDigest(algorithm: UpdateChecksumAlgorithm): DownloadDigest = JvmDownloadDigest(algorithm)

private class JvmDownloadDigest(algorithm: UpdateChecksumAlgorithm) : DownloadDigest {
    private val digest = MessageDigest.getInstance(when (algorithm) {
        UpdateChecksumAlgorithm.Sha256 -> "SHA-256"
        UpdateChecksumAlgorithm.Sha512 -> "SHA-512"
    })

    override fun update(buffer: ByteArray, count: Int): Unit = digest.update(buffer, 0, count)

    override fun hexDigest(): String = digest.digest().toHexString()
}
