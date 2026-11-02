package top.ntutn.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun updateHttpEngine(): HttpClientEngine = OkHttp.create()
public actual fun detectUpdateTarget(): TargetDetection {
    if (!Process.is64Bit()) return TargetDetection.UnsupportedPlatform
    val arch = Build.SUPPORTED_64_BIT_ABIS.firstOrNull()?.let {
        when (it) { "arm64-v8a" -> UpdateArch.ARM64; "x86_64" -> UpdateArch.X86_64; else -> null }
    } ?: return TargetDetection.UnsupportedPlatform
    return TargetDetection.Supported(UpdateTarget(UpdateOs.Android, arch))
}

/** Holds applicationContext only; no Activity is retained. */
public fun createAndroidBrowserOpener(context: Context, allowInsecureHttp: Boolean = false): BrowserOpener {
    val appContext = context.applicationContext
    return BrowserOpener { url ->
        if (!isAllowedWebUrl(url, allowInsecureHttp)) return@BrowserOpener BrowserOpenResult.Failed(BrowserOpenError.UnsafeUrl)
        withContext(Dispatchers.Main.immediate) {
            try {
                appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                BrowserOpenResult.Dispatched
            } catch (_: RuntimeException) { BrowserOpenResult.Failed(BrowserOpenError.Unavailable) }
        }
    }
}
