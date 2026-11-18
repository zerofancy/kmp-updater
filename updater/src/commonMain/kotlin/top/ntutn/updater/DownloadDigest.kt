package top.ntutn.updater

internal expect fun createDownloadDigest(algorithm: UpdateChecksumAlgorithm): DownloadDigest

internal interface DownloadDigest {
    fun update(buffer: ByteArray, count: Int)
    fun hexDigest(): String
}
