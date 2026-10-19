package com.localshare.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.documentfile.provider.DocumentFile
import com.localshare.app.server.AssetProvider
import com.localshare.app.server.MiniServer
import com.localshare.app.server.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class Device(val id: String, val name: String, val ip: String, val since: Long)

data class UiState(
    val running: Boolean = false,
    val port: Int = 8080,
    val urls: List<String> = emptyList(),
    val hostToken: String = "",
    val folderName: String? = null,
    val devices: List<Device> = emptyList(),
    val blocks: List<ServerConfig.Block> = emptyList(),
    val password: String = "",
    val maxClients: Int = 5,
    val gameEnabled: Boolean = true,
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
        cfg.setBlocks(prefs.loadBlocks())
        cfg.onChange = Runnable { prefs.saveBlocks(cfg.blocks()); refresh() }
        refresh()
    }

    // ---- settings (applied live to the running server) ----
    fun setPassword(v: String) { prefs.password = v; cfg.password = v; server?.kickAll(); refresh() }
    fun setMaxClients(v: Int) { prefs.maxClients = v; cfg.maxClients = v; refresh() }
    fun setGame(v: Boolean) { prefs.gameEnabled = v; cfg.gameEnabled = v; refresh() }
    fun setPort(v: Int) { prefs.port = v; refresh() }
    fun setFolder(uri: Uri) {
        prefs.folderUri = uri.toString()
        server?.setFileSource(SafFileSource(app, uri))
        refresh()
    }

    fun kick(id: String) { server?.kick(id); refresh() }
    fun block(id: String) { server?.block(id); refresh() }
    fun unblock(id: String) { cfg.removeBlock(id); refresh() }

    // ---- lifecycle (called by ShareService) ----
    /** @return null on success, otherwise a human-readable error */
    @Synchronized
    fun start(): String? {
        if (server != null) return null
        val folder = prefs.folderUri ?: return "Choose a folder first"
        return try {
            val s = MiniServer(cfg, SafFileSource(app, Uri.parse(folder)), AssetProvider { name ->
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
        server?.stop()
        server = null
        main.removeCallbacks(ticker)
        refresh()
    }

    fun isRunning() = server != null

    fun refresh() {
        val s = server
        val ips = if (s != null) localIps() else emptyList()
        val folder = prefs.folderUri?.let {
            try { DocumentFile.fromTreeUri(app, Uri.parse(it))?.name } catch (e: Exception) { null }
        }
        _state.value = UiState(
            running = s != null,
            port = s?.port() ?: prefs.port,
            urls = ips.map { "http://$it:${s?.port()}" },
            hostToken = s?.hostToken() ?: "",
            folderName = folder,
            devices = s?.sessions()?.map { Device(it.id, it.name, it.ip, it.since) } ?: emptyList(),
            blocks = cfg.blocks(),
            password = cfg.password,
            maxClients = cfg.maxClients,
            gameEnabled = cfg.gameEnabled,
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
