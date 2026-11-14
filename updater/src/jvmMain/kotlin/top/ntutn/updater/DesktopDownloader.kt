package top.ntutn.updater

import io.ktor.client.HttpClient
import java.io.File

/** Uses an SDK-owned subdirectory of [directory]. The injected client must disable redirects/retries/auth/cookies. */
public fun createDesktopUpdateDownloader(
    directory: File,
    options: DownloadOptions = DownloadOptions(),
    client: HttpClient? = null,
): UpdateDownloader = createHttpUpdateDownloader(JvmDownloadFileStore(File(directory, "kmp-updater"), options), options, client)
