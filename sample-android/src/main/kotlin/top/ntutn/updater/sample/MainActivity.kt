package top.ntutn.updater.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import top.ntutn.updater.*
import top.ntutn.updater.compose.AndroidUpdateHost

class MainActivity : ComponentActivity() {
    private var foreground by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val detection = detectUpdateTarget() as? TargetDetection.Supported
        val opener = createAndroidBrowserOpener(applicationContext, allowInsecureHttp = BuildConfig.DEBUG)
        setContent {
            val downloaderFactory = remember {
                { options: DownloadOptions -> createAndroidUpdateDownloader(applicationContext, options) }
            }
            detection?.let {
                SampleApp(
                    target = it.target,
                    browserOpener = opener,
                    baseUrl = intent.getStringExtra("baseUrl") ?: "https://zp.ntutn.top",
                    foreground = foreground,
                    downloaderFactory = downloaderFactory,
                    initialSoftware = (if (BuildConfig.DEBUG) intent.getStringExtra("software") else null) ?: "sdk-demo",
                    initialVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    updateHost = { controller, isForeground ->
                        AndroidUpdateHost(controller, this@MainActivity, isForeground)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
    }

    override fun onPause() {
        foreground = false
        super.onPause()
    }
}
