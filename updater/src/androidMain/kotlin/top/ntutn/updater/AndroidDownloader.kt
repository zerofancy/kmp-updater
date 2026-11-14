package top.ntutn.updater

import android.content.Context
import io.ktor.client.HttpClient
import java.io.File

/** Uses internal app files without storage permissions. No Activity is retained. */
public fun createAndroidUpdateDownloader(
    context: Context,
    options: DownloadOptions = DownloadOptions(),
    client: HttpClient? = null,
): UpdateDownloader = createHttpUpdateDownloader(
    JvmDownloadFileStore(File(context.applicationContext.filesDir, "kmp-updater"), options), options, client,
)
