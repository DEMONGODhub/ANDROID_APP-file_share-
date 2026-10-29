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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.MoreVert
import kotlinx.coroutines.launch
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
import androidx.compose.ui.graphics.Color
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

/** Asks Android, with one tap, to stop limiting this app's battery use so sharing survives the screen turning off. */
@android.annotation.SuppressLint("BatteryLife")
private fun requestUnrestricted(ctx: android.content.Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    } catch (e: Exception) {
        try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        catch (e2: Exception) { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
    }
}

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
    var tvNote by remember { mutableStateOf<String?>(null) }
    if (showDonate) DonateDialog { showDonate = false }

    fun start() {
        if (Build.VERSION.SDK_INT >= 33 && !perms.notifications) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        ShareService.start(ctx)
        // only ask while the exemption is off, and not again for 3 days after the person has seen the box
        if (Build.VERSION.SDK_INT >= 23 && !readPerms(ctx).unrestricted) {
            val sp = ctx.getSharedPreferences("battery_ask", android.content.Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - sp.getLong("at", 0L) > 3L * 24 * 3600 * 1000) {
                sp.edit().putLong("at", now).apply()
                requestUnrestricted(ctx)
            }
        }
    }

    val ls = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var chip by remember { mutableStateOf(0) }
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        state = ls,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            // YouTube-style chip row: tap one to jump to that part of the page
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Sharing", "Folders", "Access", "Support").forEachIndexed { i, label ->
                    FilterChip(
                        selected = chip == i,
                        onClick = {
                            chip = i
                            scope.launch { ls.animateScrollToItem(if (i == 4) maxOf(0, ls.layoutInfo.totalItemsCount - 1) else i) }
                        },
                        label = { Text(label, fontWeight = FontWeight.SemiBold) },
                        shape = RoundedCornerShape(50),
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }
        item { StatusCard(st, ::start, { ShareService.stop(ctx) }, { u -> clip.setText(AnnotatedString(u)) }) }
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
    st: UiState,
    onStart: () -> Unit, onStop: () -> Unit, onCopy: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    var qrFor by remember { mutableStateOf<String?>(null) }
    val brush = Brush.verticalGradient(listOf(c.surfaceContainerHigh, c.surfaceContainer))
    val fg = c.onSurface
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(brush)
            .border(1.dp, if (st.running) c.primary.copy(alpha = 0.55f) else c.outline, MaterialTheme.shapes.extraLarge).padding(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (st.running) c.primary.copy(alpha = pulse) else c.onSurfaceVariant))
                Spacer(Modifier.width(8.dp))
                Text(if (st.running) "Sharing is ON" else "Sharing is OFF", color = fg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (st.running) {
                if (st.urls.isEmpty()) Text("No network found. Connect to Wi-Fi or turn on your hotspot.", color = fg)
                Text(
                    if (st.urls.size > 1) "${st.urls.size} ways to connect (Wi-Fi and hotspot). Use any one." else "Open this address on any device on the same network.",
                    color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
                st.urls.forEach { u ->
                    Surface(color = c.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 2.dp)) {
                            Text(u, color = c.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton({ qrFor = if (qrFor == u) null else u }, colors = ButtonDefaults.textButtonColors(contentColor = c.primary)) {
                                    Text(if (qrFor == u) "Hide QR" else "QR code")
                                }
                                TextButton({ onCopy(u) }, colors = ButtonDefaults.textButtonColors(contentColor = c.primary)) { Text("Copy") }
                                TextButton(
                                    { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), "Share link")) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = c.primary),
                                ) { Text("Share") }
                            }
                        }
                    }
                }
                qrFor?.let { u ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QrCode(u, 200.dp, "QR code for $u")
                        Text("Scan with the camera to open $u", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Text(
                    if (st.gameOnly) "Game-only mode is on (change it in the Game tab): guests can play but never see your files."
                    else if (st.folders.isEmpty()) "Add a folder below to get started." else "Ready to share ${st.folders.size} ${if (st.folders.size == 1) "folder" else "folders"}.",
                    color = c.onSurfaceVariant,
                )
            }
            st.error?.let { Text(it, color = c.error) }
            Button(
                onClick = { if (st.running) onStop() else onStart() },
                enabled = st.running || st.folders.isNotEmpty() || st.gameOnly,
                colors = if (st.running) ButtonDefaults.buttonColors(containerColor = c.surfaceContainerHighest, contentColor = c.primary) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
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
        st.folders.forEachIndexed { i, f ->
            var menu by remember { mutableStateOf(false) }
            val c = MaterialTheme.colorScheme
            if (i > 0) HorizontalDivider(color = c.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_folder), null, tint = c.primary, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (st.running) "Shared folder · visible to guests" else "Shared folder", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Options for ${f.name}", tint = c.onSurfaceVariant) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Stop sharing") }, { menu = false; removing = f })
                    }
                }
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
            "Don't limit battery use", "Without this, Android can stop sharing a few minutes after you leave the app or lock the phone, which restarts games and signs guests out. Tap Allow, then choose Allow in the box that opens.",
            perms.unrestricted, "Allow",
        ) { requestUnrestricted(ctx) }
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
