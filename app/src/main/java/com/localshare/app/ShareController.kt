package com.localshare.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.documentfile.provider.DocumentFile
import com.localshare.app.server.AssetProvider
import com.localshare.app.server.CompositeFileSource
import com.localshare.app.server.FileSource
import com.localshare.app.server.MiniServer
import com.localshare.app.server.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class Device(val id: String, val name: String, val ip: String, val since: Long, val perms: Int = 0) {
    val manager get() = perms and 64 != 0
}
data class SharedFolder(val uri: String, val name: String)
data class BlockedDevice(val id: String, val name: String, val ip: String)
data class LogLine(val time: Long, val kind: String, val who: String, val detail: String)

data class UiState(
    val running: Boolean = false,
    val port: Int = 8080,
    val urls: List<String> = emptyList(),
    val hostToken: String = "",
    val folders: List<SharedFolder> = emptyList(),
    val devices: List<Device> = emptyList(),
    /** Devices waiting for the host to let them in. */
    val waiting: List<Device> = emptyList(),
    val askToJoin: Boolean = false,
    val casting: Boolean = false,
    val viewers: Int = 0,
    val blocks: List<BlockedDevice> = emptyList(),
    val log: List<LogLine> = emptyList(),
    val password: String = "",
    val maxClients: Int = 5,
    val gameEnabled: Boolean = true,
    val allowUploads: Boolean = false,
    val allowDelete: Boolean = false,
    val gameOnly: Boolean = false,
    val gameMode: Int = 0,
    val sessionHours: Int = 6,
    val error: String? = null,
)

/** Single source of truth shared by the UI and the foreground service. */
object ShareController {
    private lateinit var app: Context
    private lateinit var prefs: Prefs
    private val cfg = ServerConfig()
    private var server: MiniServer? = null
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> get() = _state
    private var ready = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (server != null) main.postDelayed(this, 2000)
        }
    }

    fun init(ctx: Context) {
        if (ready) return
        ready = true
        app = ctx.applicationContext
        prefs = Prefs(app)
        cfg.password = prefs.password
        cfg.maxClients = prefs.maxClients
        cfg.gameEnabled = prefs.gameEnabled
        cfg.allowUploads = prefs.allowUploads
        cfg.allowDelete = prefs.allowDelete
        cfg.gameOnly = prefs.gameOnly
        cfg.askToJoin = prefs.askToJoin
        cfg.gameMode = prefs.gameMode
        cfg.gameEnabled = prefs.gameEnabled || prefs.gameOnly
        cfg.loadPerms(prefs.loadPerms())
        cfg.sessionMs = prefs.sessionHours * 3600000L
        cfg.setBlocks(prefs.loadBlocks())
        cfg.onChange = Runnable { prefs.saveBlocks(cfg.blocks()); prefs.savePerms(cfg.permsSnapshot()); prefs.gameMode = cfg.gameMode; refresh() }
        refresh()
    }

    // ---- settings (applied live to the running server) ----
    fun setPassword(v: String) { prefs.password = v; cfg.password = v; server?.kickAll(); refresh() }
    fun setMaxClients(v: Int) { prefs.maxClients = v; cfg.maxClients = v; refresh() }
    fun setGame(v: Boolean) { prefs.gameEnabled = v; cfg.gameEnabled = v || prefs.gameOnly; refresh() }
    /** Game-only hosting needs no folders: guests only ever see the game. */
    fun setGameOnly(v: Boolean) { prefs.gameOnly = v; cfg.gameOnly = v; cfg.gameEnabled = prefs.gameEnabled || v; refresh() }
    fun setGameMode(m: Int) { cfg.setGameMode(m) }
    fun setAskToJoin(v: Boolean) { prefs.askToJoin = v; cfg.askToJoin = v; refresh() }
    /** The host (or a manager) lets a waiting device in, or turns it away. */
    fun approve(id: String, allow: Boolean): Boolean { val ok = server?.approve(id, allow) ?: true; refresh(); return ok }
    fun setManager(id: String, on: Boolean) { server?.setManager(id, on); refresh() }
    fun streamHub() = server?.stream()
    fun setOnStopStream(r: () -> Unit) { server?.setOnStopStream(Runnable(r)) }
    fun setDevicePerms(id: String, perms: Int) { server?.setPerms(id, perms); refresh() }
    fun setPort(v: Int) { prefs.port = v; refresh() }
    fun setUploads(v: Boolean) { prefs.allowUploads = v; cfg.allowUploads = v; refresh() }
    fun setDelete(v: Boolean) { prefs.allowDelete = v; cfg.allowDelete = v; refresh() }
    fun setSessionHours(v: Int) { prefs.sessionHours = v; cfg.sessionMs = v * 3600000L; refresh() }
    fun clearLog() { cfg.log.clear(); refresh() }

    fun addFolder(uri: Uri) {
        val s = uri.toString()
        if (s in prefs.folders) return
        prefs.folders = prefs.folders + s
        applyFolders()
    }

    fun removeFolder(uri: String) {
        prefs.folders = prefs.folders - uri
        try {
            app.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { }
        applyFolders()
    }

    private fun applyFolders() {
        server?.setFileSource(buildSource())
        refresh()
    }

    private fun folderName(uri: String): String = try {
        DocumentFile.fromTreeUri(app, Uri.parse(uri))?.name
    } catch (e: Exception) { null } ?: "Folder"

    /** Visitors see one entry per shared folder and pick which one to open. */
    private fun buildSource(): FileSource {
        val used = HashSet<String>()
        val mounts = LinkedHashMap<String, FileSource>()
        for (f in prefs.folders) mounts[CompositeFileSource.mountName(folderName(f), used)] = SafFileSource(app, Uri.parse(f))
        return CompositeFileSource(mounts)
    }

    fun deviceGameOk(): Boolean = prefs.gameEnabled || prefs.gameOnly

    fun gameRoster(): String = server?.gameRoster() ?: "[]"
    fun setSpectator(id: String, on: Boolean) { server?.setSpectator(id, on) }
    fun givePower(id: String, power: String): Int = server?.grantPower(id, power) ?: 0
    fun kick(id: String) { server?.kick(id); refresh() }
    fun block(id: String) { server?.block(id); refresh() }
    fun unblock(id: String) { cfg.removeBlock(id); refresh() }

    // ---- lifecycle (called by ShareService) ----
    /** @return null on success, otherwise a human-readable error */
    @Synchronized
    fun start(): String? {
        if (server != null) return null
        if (prefs.folders.isEmpty() && !prefs.gameOnly) return "Add a folder first, or turn on game-only mode"
        return try {
            val s = MiniServer(cfg, buildSource(), AssetProvider { name ->
                try { app.assets.open("web/$name").use { it.readBytes() } } catch (e: java.io.IOException) { null }
            })
            s.start(prefs.port)
            server = s
            main.post(ticker)
            null
        } catch (e: Exception) {
            val msg = "Could not start on port ${prefs.port}: ${e.message}"
            _state.value = _state.value.copy(error = msg)
            msg
        }
    }

    @Synchronized
    fun stop() {
        CastService.stop(app)
        server?.stop()
        server = null
        main.removeCallbacks(ticker)
        refresh()
    }

    fun isRunning() = server != null

    fun refresh() {
        val s = server
        val ips = if (s != null) localIps() else emptyList()
        _state.value = UiState(
            running = s != null,
            port = s?.port() ?: prefs.port,
            urls = ips.map { "http://$it:${s?.port()}" },
            hostToken = s?.hostToken() ?: "",
            folders = prefs.folders.map { SharedFolder(it, folderName(it)) },
            devices = s?.sessions()?.filter { !it.pending }?.map { Device(it.id, it.name, it.ip, it.since, it.perms) } ?: emptyList(),
            waiting = s?.sessions()?.filter { it.pending }?.map { Device(it.id, it.name, it.ip, it.since, it.perms) } ?: emptyList(),
            askToJoin = cfg.askToJoin,
            casting = CastService.active && s != null,
            viewers = s?.stream()?.viewers() ?: 0,
            blocks = cfg.blocks().map { BlockedDevice(it.id, it.name, it.ip) },
            log = if (s != null) cfg.log.recent().take(100).map { LogLine(it.time, it.kind, it.who, it.detail) } else emptyList(),
            password = cfg.password,
            maxClients = cfg.maxClients,
            gameEnabled = cfg.gameEnabled,
            allowUploads = cfg.allowUploads,
            allowDelete = cfg.allowDelete,
            gameOnly = cfg.gameOnly,
            gameMode = cfg.gameMode,
            sessionHours = prefs.sessionHours,
            error = _state.value.error.takeIf { s == null },
        )
    }

    /** Private-network IPv4 addresses (Wi-Fi, hotspot, ethernet) — never mobile-data addresses. */
    private fun localIps(): List<String> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
            .map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
    } catch (e: Exception) { emptyList() }
}
