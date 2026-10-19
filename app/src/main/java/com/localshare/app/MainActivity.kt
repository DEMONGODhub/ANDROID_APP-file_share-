package com.localshare.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
    val st by ShareController.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var browser by rememberSaveable { mutableStateOf(false) }

    if (browser && st.running) {
        HostBrowser(st) { browser = false }
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
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ShareTab(st) { browser = true }
                1 -> DevicesTab(st)
                else -> SettingsTab(st)
            }
        }
    }
}

// ------------------------------------------------------------------ Share

@Composable
private fun ShareTab(st: UiState, openBrowser: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ShareController.setFolder(uri)
        }
    }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ShareService.start(ctx) }
    fun start() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else ShareService.start(ctx)
    }

    var showDonate by remember { mutableStateOf(false) }
    if (showDonate) DonateDialog { showDonate = false }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("LocalShare", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Share a folder with devices on your Wi-Fi or hotspot", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            val c = MaterialTheme.colorScheme
            val brush = if (st.running) Brush.linearGradient(listOf(c.primary, c.secondary)) else Brush.linearGradient(listOf(c.surfaceVariant, c.surfaceVariant))
            val fg = if (st.running) c.onPrimary else c.onSurface
            Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(brush).padding(22.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (st.running) fg else c.onSurfaceVariant))
                        Spacer(Modifier.width(8.dp))
                        Text(if (st.running) "Sharing is ON" else "Sharing is OFF", color = fg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    if (st.running) {
                        if (st.urls.isEmpty()) Text("No network found — connect to Wi-Fi or turn on your hotspot.", color = fg)
                        st.urls.forEach { u ->
                            Surface(color = fg.copy(alpha = .14f), shape = MaterialTheme.shapes.medium) {
                                Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(u, color = fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    TextButton({ clip.setText(AnnotatedString(u)) }, colors = ButtonDefaults.textButtonColors(contentColor = fg)) { Text("Copy") }
                                    IconButton({
                                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), "Share link"))
                                    }) { Icon(Icons.Filled.Share, "Share link", tint = fg) }
                                }
                            }
                        }
                        Text("Open this address in a browser on any device on the same network.", color = fg.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(if (st.folderName == null) "Pick a folder to get started." else "Ready to share “${st.folderName}”.", color = c.onSurfaceVariant)
                    }
                    st.error?.let { Text(it, color = c.error) }
                    Button(
                        onClick = { if (st.running) ShareService.stop(ctx) else start() },
                        enabled = st.running || st.folderName != null,
                        colors = if (st.running) ButtonDefaults.buttonColors(containerColor = c.background, contentColor = c.onBackground) else ButtonDefaults.buttonColors(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (st.running) "Stop sharing" else "Start sharing", fontWeight = FontWeight.Bold) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_folder), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Shared folder", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        Text(st.folderName ?: "None selected", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalButton({ picker.launch(null) }) { Text(if (st.folderName == null) "Choose" else "Change") }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Enjoying LocalShare?", fontWeight = FontWeight.SemiBold)
                        Text("Support its development with a small donation", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(12.dp))
                    Button({ showDonate = true }) { Text("Donate") }
                }
            }
        }
        if (st.running) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Connected: ${st.devices.size} / ${st.maxClients} devices", fontWeight = FontWeight.SemiBold)
                        OutlinedButton(openBrowser, Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Browse files & play the game here")
                        }
                        if (!st.gameEnabled) Text("The game is turned off in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Donate

private const val UPI_ID = "9813986600@fam"
private const val UPI_LINK = "upi://pay?pa=$UPI_ID&pn=LocalShare&cu=INR"

@Composable
private fun DonateDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var note by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Support LocalShare") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Scan with any UPI app (Google Pay, PhonePe, Paytm, BHIM) or tap the button below.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                // Always white behind the code so it scans in dark mode too.
                Image(
                    painterResource(R.drawable.qr_upi), "UPI QR code for $UPI_ID",
                    Modifier.size(210.dp).clip(MaterialTheme.shapes.medium).background(androidx.compose.ui.graphics.Color.White),
                )
                Text(UPI_ID, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ clip.setText(AnnotatedString(UPI_ID)); note = "UPI ID copied" }) { Text("Copy ID") }
                    Button({
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(UPI_LINK)))
                        } catch (e: android.content.ActivityNotFoundException) {
                            note = "No UPI app found. Scan the code from another phone."
                        }
                    }) { Text("Pay with UPI app") }
                }
                note?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

// ------------------------------------------------------------------ Devices

@Composable
private fun DevicesTab(st: UiState) {
    var confirm by remember { mutableStateOf<Device?>(null) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Devices", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Connected ${st.devices.size} of ${st.maxClients}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (st.devices.isEmpty()) item {
            Text(if (st.running) "Nobody is connected yet." else "Start sharing to let devices connect.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
        }
        items(st.devices, key = { it.id }) { d ->
            DeviceRow(d.name, d.ip) {
                TextButton({ ShareController.kick(d.id) }) { Text("Kick") }
                Button({ confirm = d }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text("Block") }
            }
        }
        item { Spacer(Modifier.height(10.dp)); Text("Blocked", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (st.blocks.isEmpty()) item { Text("No blocked devices.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(st.blocks, key = { it.id }) { b ->
            DeviceRow(b.name, b.ip.ifEmpty { "device id" }, blocked = true) {
                OutlinedButton({ ShareController.unblock(b.id) }) { Text("Unblock") }
            }
        }
        item {
            Text(
                "Blocking removes the device now and refuses it again by IP address and by its browser's device ID. Android doesn't let apps read other devices' MAC addresses, so a person who changes both can get back in — set a password too.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    confirm?.let { d ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Block ${d.name}?") },
            text = { Text("They will be disconnected and can't rejoin from ${d.ip}. You can unblock them any time.") },
            confirmButton = { TextButton({ ShareController.block(d.id); confirm = null }) { Text("Block", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeviceRow(name: String, sub: String, blocked: Boolean = false, actions: @Composable RowScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (blocked) MaterialTheme.colorScheme.error.copy(alpha = .18f) else MaterialTheme.colorScheme.primary.copy(alpha = .18f)),
                contentAlignment = Alignment.Center,
            ) {
                if (blocked) Icon(painterResource(R.drawable.ic_block), null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                else Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            actions()
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsTab(st: UiState) {
    var pw by remember(st.password) { mutableStateOf(st.password) }
    var show by remember { mutableStateOf(false) }
    var port by remember(st.port) { mutableStateOf(st.port.toString()) }
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            Card(colors = cardColors, border = border) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Password", fontWeight = FontWeight.SemiBold)
                    Text("Required to enter the folder. Leave empty for an open room. Changing it signs everyone out.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        pw, { pw = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Password") },
                        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton({ show = !show }) {
                                Icon(
                                    painterResource(if (show) R.drawable.ic_eye_off else R.drawable.ic_eye),
                                    contentDescription = if (show) "Hide password" else "Show password",
                                )
                            }
                        },
                    )
                    Button({ ShareController.setPassword(pw) }, enabled = pw != st.password, modifier = Modifier.align(Alignment.End)) { Text("Save password") }
                }
            }
        }
        item {
            Card(colors = cardColors, border = border) {
                Column(Modifier.padding(16.dp)) {
                    Row { Text("Max devices", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text("${st.maxClients}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                    Text("Extra people see “Room is full” until someone leaves.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Slider(st.maxClients.toFloat(), { ShareController.setMaxClients(it.toInt()) }, valueRange = 1f..20f, steps = 18)
                }
            }
        }
        item {
            Card(colors = cardColors, border = border) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_gamepad), null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Orb Arena game", fontWeight = FontWeight.SemiBold)
                        Text("Multiplayer mini-game in the corner of the page", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(st.gameEnabled, { ShareController.setGame(it) })
                }
            }
        }
        item {
            Card(colors = cardColors, border = border) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Port", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        port, { v -> port = v.filter { it.isDigit() }.take(5); port.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { ShareController.setPort(it) } },
                        singleLine = true, enabled = !st.running, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (st.running) "Stop sharing to change" else "1024 – 65535") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        }
        item {
            Text(
                "Traffic on your Wi-Fi is not encrypted (plain HTTP). Only share with networks you trust, and don't reuse an important password here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        }
        item { AboutCard() }
    }
}

// ------------------------------------------------------------------ About

private const val SITE = "https://demongod.freedev.app"

/** Add a pair here to list another contributor or credit. */
private val CREDITS = listOf(
    "DEMON GOD (@demongod)" to "Creator and developer",
    "PDF.js, Mozilla" to "PDF viewing in the browser page (Apache License 2.0)",
    "Jetpack Compose and AndroidX, Google" to "App interface (Apache License 2.0)",
)

@Composable
private fun AboutCard() {
    val ctx = LocalContext.current
    val version = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("About", fontWeight = FontWeight.SemiBold)
            Text(if (version.isEmpty()) "LocalShare" else "LocalShare $version", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Share a folder from your phone with any device on your Wi-Fi or hotspot.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SITE)))
                    } catch (e: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(ctx, "No browser found on this phone", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                Modifier.fillMaxWidth(),
            ) { Text("demongod.freedev.app") }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Text("Contributors and credits", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            CREDITS.forEach { (name, role) ->
                Column {
                    Text(name, fontWeight = FontWeight.SemiBold)
                    Text(role, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Host browser (files + game, no password needed)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HostBrowser(st: UiState, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("← Back to host") }
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
                    setBackgroundColor(0xFF0B0F14.toInt())
                    webViewClient = WebViewClient()
                    loadUrl("http://127.0.0.1:${st.port}/#ht=${st.hostToken}")
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
