package com.localshare.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.localshare.app.ui.SectionCard

private const val SITE = "https://demongod.freedev.app"

/** Add a pair here to list another contributor or credit. */
private val CREDITS = listOf(
    "DEMON_GOD (@demongod)" to "Creator and developer",
    "PDF.js, Mozilla" to "PDF viewing in the browser page (Apache License 2.0)",
    "Jetpack Compose and AndroidX, Google" to "App interface (Apache License 2.0)",
    "ZXing" to "QR codes (Apache License 2.0)",
)

private val SESSION_CHOICES = listOf(1 to "1 hour", 6 to "6 hours", 24 to "1 day", 0 to "Until I stop")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(st: UiState) {
    var pw by remember(st.password) { mutableStateOf(st.password) }
    var show by remember { mutableStateOf(false) }
    var port by remember(st.port) { mutableStateOf(st.port.toString()) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }) }
        item {
            SectionCard {
                Text("Password", fontWeight = FontWeight.SemiBold)
                Text("Needed to enter. Leave empty for an open room. Changing it signs everyone out.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    pw, { pw = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Password") },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton({ show = !show }) {
                            Icon(painterResource(if (show) R.drawable.ic_eye_off else R.drawable.ic_eye), if (show) "Hide password" else "Show password")
                        }
                    },
                )
                Button({ ShareController.setPassword(pw) }, enabled = pw != st.password, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("Save password") }
            }
        }
        item {
            SectionCard {
                Row { Text("Max devices", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text("${st.maxClients}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                Text("Extra people see “Room is full” until someone leaves.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Slider(st.maxClients.toFloat(), { ShareController.setMaxClients(it.toInt()) }, valueRange = 1f..20f, steps = 18)
            }
        }
        item {
            SectionCard {
                Text("What guests can do", fontWeight = FontWeight.SemiBold)
                SwitchRow("Ask before anyone joins", "New devices wait until you tap Allow in the Devices tab", st.askToJoin) { ShareController.setAskToJoin(it) }
                SwitchRow("Upload files", "Guests can add files to a folder", st.allowUploads) { ShareController.setUploads(it) }
                SwitchRow("Delete files", "Guests can remove files from your phone", st.allowDelete) { ShareController.setDelete(it) }
                Text("You can always do both from the page opened here.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SectionCard {
                Text("Stay signed in for", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SESSION_CHOICES.forEach { (h, label) -> FilterChip(st.sessionHours == h, { ShareController.setSessionHours(h) }, { Text(label) }) }
                }
            }
        }
        item {
            SectionCard {
                Text("Port", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    port, { v -> port = v.filter { it.isDigit() }.take(5); port.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { ShareController.setPort(it) } },
                    singleLine = true, enabled = !st.running, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (st.running) "Stop sharing to change" else "1024 – 65535") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
        item {
            Text(
                "Traffic on your Wi-Fi is not encrypted (plain HTTP). Only share on networks you trust, and don't reuse an important password here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        }
        item { AboutCard() }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, null)
    }
}

@Composable
private fun AboutCard() {
    val ctx = LocalContext.current
    val version = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    }
    SectionCard {
        Text("About", fontWeight = FontWeight.SemiBold)
        Text(if (version.isEmpty()) "LocalShare" else "LocalShare $version", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Share folders from your phone with any device on your Wi-Fi or hotspot.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE))) }
                catch (e: ActivityNotFoundException) { android.widget.Toast.makeText(ctx, "No browser found on this phone", android.widget.Toast.LENGTH_SHORT).show() }
            },
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("demongod.freedev.app") }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text("Contributors and credits", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        CREDITS.forEach { (name, role) ->
            Column {
                Text(name, fontWeight = FontWeight.SemiBold)
                Text(role, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text("© 2026 DEMON_GOD. All rights reserved.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}
