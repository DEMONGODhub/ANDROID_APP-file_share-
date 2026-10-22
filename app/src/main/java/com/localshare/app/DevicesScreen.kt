package com.localshare.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.text.format.DateUtils
import com.localshare.app.ui.SectionCard

@Composable
fun DevicesScreen(st: UiState) {
    var confirm by remember { mutableStateOf<Device?>(null) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Devices", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text("Connected ${st.devices.size} of ${st.maxClients}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (st.devices.isEmpty()) item {
            Text(if (st.running) "Nobody is connected yet." else "Start sharing to let devices connect.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
        }
        items(st.devices, key = { it.id }) { d ->
            DeviceRow(d.name, d.ip) {
                TextButton({ ShareController.kick(d.id) }, Modifier.heightIn(min = 48.dp)) { Text("Kick") }
                Button({ confirm = d }, Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text("Block") }
            }
        }
        item { Spacer(Modifier.height(10.dp)); Text("Blocked", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }) }
        if (st.blocks.isEmpty()) item { Text("No blocked devices.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(st.blocks, key = { it.id }) { b ->
            DeviceRow(b.name, b.ip.ifEmpty { "device ID" }, blocked = true) {
                OutlinedButton({ ShareController.unblock(b.id) }, Modifier.heightIn(min = 48.dp)) { Text("Unblock") }
            }
        }
        item {
            Text(
                "Blocking removes the device now and refuses it again by IP address and by its browser's device ID. Android does not let apps read other devices' MAC addresses, so someone who changes both can get back in. Set a password too.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    confirm?.let { d ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Block ${d.name}?") },
            text = { Text("They will be disconnected and cannot rejoin from ${d.ip}. You can unblock them any time.") },
            confirmButton = { TextButton({ ShareController.block(d.id); confirm = null }) { Text("Block", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeviceRow(name: String, sub: String, blocked: Boolean = false, actions: @Composable RowScope.() -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
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

private fun describe(l: LogLine): String = when (l.kind) {
    "join" -> "joined"
    "view" -> "opened ${l.detail}"
    "download" -> "downloaded ${l.detail}"
    "zip" -> "downloaded ZIP of ${l.detail}"
    "upload" -> "uploaded ${l.detail}"
    "delete" -> "deleted ${l.detail}"
    "kick" -> "was removed by you"
    "block" -> "was blocked by you"
    "blocked" -> "tried to join but is blocked"
    "denied" -> "was refused (${l.detail})"
    "full" -> "could not join: room full"
    else -> l.detail.ifEmpty { l.kind }
}

@Composable
fun ActivityScreen(st: UiState) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Activity", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text("What people did on your shared folders", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (st.log.isNotEmpty()) TextButton({ ShareController.clearLog() }, Modifier.heightIn(min = 48.dp)) { Text("Clear") }
            }
        }
        if (st.log.isEmpty()) item {
            Text(if (st.running) "Nothing yet." else "Start sharing to see activity here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
        }
        items(st.log, key = { "${it.time}${it.who}${it.kind}${it.detail}" }) { l ->
            SectionCard {
                Text("${l.who.ifEmpty { "Someone" }} ${describe(l)}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(DateUtils.getRelativeTimeSpanString(l.time, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
