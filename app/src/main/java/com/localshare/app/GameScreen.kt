package com.localshare.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localshare.app.ui.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray

private data class Gamer(val id: String, val name: String, val hp: Int, val score: Int, val dead: Boolean, val spec: Boolean)

private val POWERS = listOf(
    "speed" to "Speed Surge (15 s)",
    "rapid" to "Rapid Fire (15 s, free shots)",
    "power" to "Power Shot (15 s, 15 damage)",
    "shield" to "Iron Shield (150 HP)",
    "invincible" to "Invincible (8 s)",
)

private fun parse(json: String): List<Gamer> = try {
    val a = JSONArray(json)
    (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Gamer(o.getString("id"), o.getString("name"), o.optInt("hp"), o.optInt("score"), o.optBoolean("dead"), o.optBoolean("spec"))
    }
} catch (e: Exception) { emptyList() }

/** Everything about the game in one place: modes, the host's own play and observe, spectators and super powers. */
@Composable
fun GameScreen(st: UiState, open: (String) -> Unit) {
    var players by remember { mutableStateOf<List<Gamer>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(st.running) {
        while (true) {
            players = if (st.running) withContext(Dispatchers.Default) { parse(ShareController.gameRoster()) } else emptyList()
            delay(1500)
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Game control center", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text("Orb Arena: modes, your own seat, spectators and super powers", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionCard {
                Toggle("Orb Arena game", if (st.gameOnly) "Always on in game-only mode" else "Guests get a full-screen multiplayer game", st.gameEnabled) { if (!st.gameOnly) ShareController.setGame(it) }
                Toggle("Game-only mode", "Guests only see the game, never your files. No folders needed.", st.gameOnly) { ShareController.setGameOnly(it) }
                Text("Game mode", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(st.gameMode == 0, { ShareController.setGameMode(0) }, { Text("PvP") })
                    FilterChip(st.gameMode == 1, { ShareController.setGameMode(1) }, { Text("Battle Royale") })
                }
                Text(
                    if (st.gameMode == 1) "Rounds with a shrinking safe zone. The last player standing wins." else "Endless fights. You respawn after you are out.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            SectionCard {
                Text("You, the host", fontWeight = FontWeight.SemiBold)
                Text(
                    if (st.running) "Play like any guest, or leave the arena and watch everything live. Pinch to zoom, drag to move, follow any player."
                    else "Start sharing on the Share tab first.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button({ open("play") }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = st.running) { Text("Play") }
                    OutlinedButton({ open("obs") }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = st.running) { Text("Observe live") }
                }
            }
        }
        item {
            SectionCard {
                Text("Give everyone a power", fontWeight = FontWeight.SemiBold)
                PowerMenu("Everyone", enabled = st.running && players.isNotEmpty()) { p -> note = if (ShareController.givePower("all", p) > 0) "Given to everyone" else "Nobody to give it to yet" }
                Text("Powers also change the player's picture to a pixel hero while they last. Only you can give them.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (note.isNotEmpty()) Text(note, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text(if (players.isEmpty()) "Players" else "Players (${players.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }) }
        if (players.isEmpty()) item {
            Text(
                if (st.running) "Nobody is in the arena yet. Guests join from the game button on their page." else "Players appear here once sharing is on.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(players, key = { it.id }) { p ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (p.spec) "Spectating" else if (p.dead) "Out" else "${p.score} pumpkins · ${p.hp} HP",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    PowerMenu("Power", enabled = !p.spec) { power -> note = if (ShareController.givePower(p.id, power) > 0) "Gave ${p.name} a power" else "Could not give it" }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton({ ShareController.setSpectator(p.id, !p.spec); players = players.map { if (it.id == p.id) it.copy(spec = !p.spec) else it } }, Modifier.heightIn(min = 40.dp)) {
                        Text(if (p.spec) "Let play" else "Spectator")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun PowerMenu(label: String, enabled: Boolean, onPick: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ show = true }, Modifier.heightIn(min = 40.dp), enabled = enabled) { Text(label) }
        DropdownMenu(show, { show = false }) {
            POWERS.forEach { (id, text) -> DropdownMenuItem({ Text(text) }, { show = false; onPick(id) }) }
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
