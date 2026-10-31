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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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

    var joinUrl by rememberSaveable { mutableStateOf<String?>(null) } // another phone's share, opened inside this app
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val castLauncher = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val d = res.data
        if (res.resultCode == android.app.Activity.RESULT_OK && d != null) CastService.start(ctx, res.resultCode, d)
    }
    val startCast = {
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        castLauncher.launch(mpm.createScreenCaptureIntent())
    }
    joinUrl?.let { u ->
        HostBrowser(0, "", "", { joinUrl = null }, u)
        return
    }
    val mode = browser
    if (mode != null && st.running) {
        HostBrowser(st.port, st.hostToken, mode) { browser = null }
        return
    }
    BackHandler(enabled = tab != 0) { tab = 0 } // Back from any other tab goes to Share first
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppBar(st.running, st.devices.size) },
        bottomBar = {
          Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            val nav = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                NavigationBarItem(tab == 0, { tab = 0 }, { NavIcon(tab == 0) { Icon(Icons.Filled.Home, null) } }, label = { Text("Share") }, colors = nav)
                NavigationBarItem(tab == 1, { tab = 1 }, { NavIcon(tab == 1) {
                    BadgedBox(badge = { if (st.waiting.isNotEmpty()) Badge { Text("${st.waiting.size}") } else if (st.devices.isNotEmpty()) Badge { Text("${st.devices.size}") } }) { Icon(Icons.Filled.Person, null) }
                } }, label = { Text("Devices") }, colors = nav)
                NavigationBarItem(tab == 2, { tab = 2 }, { NavIcon(tab == 2) { Icon(painterResource(R.drawable.ic_gamepad), null) } }, label = { Text("Game") }, colors = nav)
                NavigationBarItem(tab == 3, { tab = 3 }, { NavIcon(tab == 3) { Icon(Icons.Filled.Info, null) } }, label = { Text("Activity") }, colors = nav)
                NavigationBarItem(tab == 4, { tab = 4 }, { NavIcon(tab == 4) { Icon(Icons.Filled.Settings, null) } }, label = { Text("Settings") }, colors = nav)
            }
          }
        },
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
            Crossfade(tab, animationSpec = tween(220), label = "tab") { t ->
                when (t) {
                    0 -> HomeScreen(st, { browser = "" }, startCast) { l ->
                        val t = l.trim()
                        joinUrl = if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
                    }
                    1 -> DevicesScreen(st)
                    2 -> GameScreen(st) { browser = it }
                    3 -> ActivityScreen(st)
                    else -> SettingsScreen(st)
                }
            }
        }
    }
}

/** Icons spring a little when their tab is picked. */
@Composable
private fun NavIcon(selected: Boolean, content: @Composable () -> Unit) {
    val s by androidx.compose.animation.core.animateFloatAsState(
        if (selected) 1.2f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 500f), label = "nav",
    )
    Box(Modifier.graphicsLayer { scaleX = s; scaleY = s }) { content() }
}

/** YouTube-style top bar: logo tile and name on the left, a LIVE pill and settings on the right. */
@Composable
private fun AppBar(running: Boolean, devices: Int) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(c.background).statusBarsPadding().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_folder), null, tint = c.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text("LocalShare", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(if (running) c.primary else c.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "live").animateFloat(
                        0.25f, 1f,
                        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "dot",
                    )
                    Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.onPrimary.copy(alpha = pulse)))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (running) "LIVE · $devices" else "OFF", fontWeight = FontWeight.Bold,
                    color = if (running) c.onPrimary else c.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
    }
}

/** The host sees the same page guests see, signed in automatically from this phone. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HostBrowser(port: Int, token: String, mode: String, onClose: () -> Unit, url: String? = null) {
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
            TextButton(onClose, Modifier.heightIn(min = 48.dp)) { Text(if (url != null) "← Back" else "← Back to host") }
            Spacer(Modifier.weight(1f))
            Text(if (url != null) "Guest view" else "You're the host", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 14.dp))
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
                    loadUrl(url ?: ("http://127.0.0.1:$port/#ht=$token" + if (mode.isEmpty()) "" else "&g=$mode"))
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
