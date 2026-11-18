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
    JvmDownloadFileStore(androidUpdateDirectory(context), options, allowApkHandoff = true), options, client,
)

internal fun androidUpdateDirectory(context: Context): File = File(context.applicationContext.filesDir, "kmp-updater")
