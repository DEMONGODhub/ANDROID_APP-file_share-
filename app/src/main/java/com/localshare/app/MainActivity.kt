package com.localshare.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localshare.app.ui.LocalShareTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ShareController.init(this)
        setContent { LocalShareTheme { App() } }
    }
}

@Composable
private fun App() {
    val st by ShareController.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var browser by rememberSaveable { mutableStateOf<String?>(null) } // null = closed, "" = files and game, "play" / "obs" = straight into the game

    val mode = browser
    if (mode != null && st.running) {
        HostBrowser(st.port, st.hostToken, mode) { browser = null }
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, null) }, label = { Text("Share") })
                NavigationBarItem(tab == 1, { tab = 1 }, {
                    BadgedBox(badge = { if (st.devices.isNotEmpty()) Badge { Text("${st.devices.size}") } }) { Icon(Icons.Filled.Person, null) }
                }, label = { Text("Devices") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(painterResource(R.drawable.ic_gamepad), null) }, label = { Text("Game") })
                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Filled.Info, null) }, label = { Text("Activity") })
                NavigationBarItem(tab == 4, { tab = 4 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(st) { browser = "" }
                1 -> DevicesScreen(st)
                2 -> GameScreen(st) { browser = it }
                3 -> ActivityScreen(st)
                else -> SettingsScreen(st)
            }
        }
    }
}

/** The host sees the same page guests see, signed in automatically from this phone. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HostBrowser(port: Int, token: String, mode: String, onClose: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    // lets the host page's Upload button and profile picture chooser open Android's file picker
    var pending by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        pending?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        pending = null
    }
    BackHandler { web?.takeIf { it.canGoBack() }?.goBack() ?: onClose() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose, Modifier.heightIn(min = 48.dp)) { Text("← Back to host") }
            Spacer(Modifier.weight(1f))
            Text("You're the host", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 14.dp))
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    setBackgroundColor(0xFF000000.toInt())
                    webViewClient = WebViewClient()
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(w: WebView?, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                            pending?.onReceiveValue(null)
                            pending = cb
                            return try { chooser.launch(params.createIntent()); true } catch (e: Exception) { pending = null; cb.onReceiveValue(null); false }
                        }
                    }
                    setDownloadListener { url, agent, disposition, mime, _ -> download(ctx, url, agent, disposition, mime) }
                    loadUrl("http://127.0.0.1:$port/#ht=$token" + if (mode.isEmpty()) "" else "&g=$mode")
                    web = this
                }
            },
            onRelease = { it.destroy(); web = null },
        )
    }
}

private fun download(ctx: Context, url: String, agent: String?, disposition: String?, mime: String?) {
    try {
        val req = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mime)
            .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            .addRequestHeader("User-Agent", agent ?: "")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, disposition, mime))
        (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        Toast.makeText(ctx, "Downloading to your Downloads folder", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Could not start the download", Toast.LENGTH_SHORT).show()
    }
}
