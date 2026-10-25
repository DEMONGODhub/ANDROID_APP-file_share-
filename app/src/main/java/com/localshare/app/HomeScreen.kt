package com.localshare.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.localshare.app.ui.QrCode
import com.localshare.app.ui.SectionCard

private data class Perms(val notifications: Boolean, val unrestricted: Boolean)

private fun readPerms(ctx: android.content.Context): Perms {
    val notif = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    val battery = Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(ctx.packageName)
    return Perms(notif, battery)
}

@Composable
fun HomeScreen(st: UiState, openBrowser: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var perms by remember { mutableStateOf(readPerms(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { perms = readPerms(ctx) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            ShareController.addFolder(uri)
        }
    }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perms = readPerms(ctx) }
    var showDonate by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var tvNote by remember { mutableStateOf<String?>(null) }
    if (showDonate) DonateDialog { showDonate = false }

    fun start() {
        if (Build.VERSION.SDK_INT >= 33 && !perms.notifications) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        ShareService.start(ctx)
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("LocalShare", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text("Share folders with devices on your Wi-Fi or hotspot", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { StatusCard(st, showQr, { showQr = !showQr }, ::start, { ShareService.stop(ctx) }, { u -> clip.setText(AnnotatedString(u)) }) }
        item { FoldersCard(st, { picker.launch(null) }) }
        item { PermissionsCard(perms) { p -> notif.launch(p) } }
        if (st.running) item {
            SectionCard {
                Text("Connected: ${st.devices.size} of ${st.maxClients} devices", fontWeight = FontWeight.SemiBold)
                OutlinedButton(openBrowser, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open the page here (files and game)")
                }
                OutlinedButton({ SendToTv.scan(ctx, st) { tvNote = it } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Send to TV (scan the code on your TV)")
                }
                tvNote?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Enjoying LocalShare?", fontWeight = FontWeight.SemiBold)
                        Text("Even ₹1 helps me keep building it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(12.dp))
                    Button({ showDonate = true }, Modifier.heightIn(min = 48.dp)) { Text("Donate") }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    st: UiState, showQr: Boolean, toggleQr: () -> Unit,
    onStart: () -> Unit, onStop: () -> Unit, onCopy: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val brush = if (st.running) Brush.linearGradient(listOf(c.primaryContainer, c.secondary)) else Brush.linearGradient(listOf(c.surfaceVariant, c.surfaceVariant))
    val fg = if (st.running) c.onPrimaryContainer else c.onSurface
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(brush).padding(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (st.running) fg else c.onSurfaceVariant))
                Spacer(Modifier.width(8.dp))
                Text(if (st.running) "Sharing is ON" else "Sharing is OFF", color = fg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (st.running) {
                if (st.urls.isEmpty()) Text("No network found. Connect to Wi-Fi or turn on your hotspot.", color = fg)
                st.urls.forEach { u ->
                    Surface(color = fg.copy(alpha = .14f), shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(u, color = fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton({ onCopy(u) }, colors = ButtonDefaults.textButtonColors(contentColor = fg)) { Text("Copy") }
                            IconButton({
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), "Share link"))
                            }) { Icon(Icons.Filled.Share, "Share link", tint = fg) }
                        }
                    }
                }
                if (st.urls.isNotEmpty()) {
                    TextButton(toggleQr, colors = ButtonDefaults.textButtonColors(contentColor = fg)) { Text(if (showQr) "Hide QR code" else "Show QR code to scan") }
                    if (showQr) QrCode(st.urls.first(), 200.dp, "QR code for ${st.urls.first()}", Modifier.align(Alignment.CenterHorizontally))
                }
                Text("Open this address in a browser on any device on the same network.", color = fg.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    if (st.gameOnly) "Game-only mode: guests can play but never see your files."
                    else if (st.folders.isEmpty()) "Add a folder below to get started." else "Ready to share ${st.folders.size} ${if (st.folders.size == 1) "folder" else "folders"}.",
                    color = c.onSurfaceVariant,
                )
            }
            st.error?.let { Text(it, color = c.error) }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(st.gameOnly, role = Role.Switch) { ShareController.setGameOnly(it) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Game-only mode", color = fg, fontWeight = FontWeight.SemiBold)
                    Text("Host just the game. No folders needed, no files visible.", color = fg.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall)
                }
                Switch(st.gameOnly, null)
            }
            Button(
                onClick = { if (st.running) onStop() else onStart() },
                enabled = st.running || st.folders.isNotEmpty() || st.gameOnly,
                colors = if (st.running) ButtonDefaults.buttonColors(containerColor = c.background, contentColor = c.onBackground) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (st.running) "Stop sharing" else "Start sharing", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun FoldersCard(st: UiState, onAdd: () -> Unit) {
    var removing by remember { mutableStateOf<SharedFolder?>(null) }
    SectionCard {
        Text("Shared folders", fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text("People who join pick which of these to open.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (st.folders.isEmpty()) Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        st.folders.forEach { f ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_folder), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(f.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({ removing = f }) { Icon(Icons.Filled.Close, "Stop sharing ${f.name}") }
            }
        }
        FilledTonalButton(onAdd, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(if (st.folders.isEmpty()) "Add a folder" else "Add another folder")
        }
    }
    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Stop sharing ${f.name}?") },
            text = { Text("Your files stay where they are. Only the sharing stops.") },
            confirmButton = { TextButton({ ShareController.removeFolder(f.uri); removing = null }) { Text("Stop sharing") } },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PermissionsCard(perms: Perms, askNotifications: (String) -> Unit) {
    val ctx = LocalContext.current
    SectionCard {
        Text("Keep sharing when the screen is locked", fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(
            "LocalShare only needs these so Android does not put it to sleep. It never reads anything outside the folders you add.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
        if (Build.VERSION.SDK_INT >= 33) PermRow(
            "Show a notice while sharing", "Android needs this small notice to let sharing keep running with the screen off. You can stop sharing from it.",
            perms.notifications, "Allow",
        ) { askNotifications(Manifest.permission.POST_NOTIFICATIONS) }
        if (Build.VERSION.SDK_INT >= 23) PermRow(
            "Don't limit battery use", "Some phones stop apps in the background to save power. Choose “All apps”, find LocalShare, and pick “Don't optimize”.",
            perms.unrestricted, "Open",
        ) { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        Text(
            "Xiaomi, Oppo, Vivo and Samsung phones also have an “Autostart” or “Battery” switch in the app's settings. Turn it on if sharing stops by itself.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
        TextButton({
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }, Modifier.heightIn(min = 48.dp)) { Text("Open app settings") }
    }
}

@Composable
private fun PermRow(title: String, why: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.Check else Icons.Filled.Warning,
            if (ok) "Done" else "Needs attention",
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(why, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (!ok) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick, Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}
