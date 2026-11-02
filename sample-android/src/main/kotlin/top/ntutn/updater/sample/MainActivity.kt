package top.ntutn.updater.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.ntutn.updater.*

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
            detection?.let {
                SampleApp(it.target, opener, intent.getStringExtra("baseUrl") ?: "https://zp.ntutn.top", foreground)
            }
        }
    }
    override fun onStart() { super.onStart(); foreground = true }
    override fun onStop() { foreground = false; super.onStop() }
}
