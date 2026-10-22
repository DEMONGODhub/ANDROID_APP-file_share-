package com.localshare.app

import android.content.Context
import com.localshare.app.server.ServerConfig

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("localshare", Context.MODE_PRIVATE)

    /** Shared folders (tree URIs), in the order they were added. */
    var folders: List<String>
        get() {
            val saved = (sp.getString("folders", null) ?: "").lines().filter { it.isNotBlank() }
            val legacy = sp.getString("folder", null)
            return if (saved.isEmpty() && legacy != null) listOf(legacy) else saved
        }
        set(v) = sp.edit().putString("folders", v.joinToString("\n")).remove("folder").apply()

    var allowUploads: Boolean
        get() = sp.getBoolean("uploads", false)
        set(v) = sp.edit().putBoolean("uploads", v).apply()

    var allowDelete: Boolean
        get() = sp.getBoolean("delete", false)
        set(v) = sp.edit().putBoolean("delete", v).apply()

    /** How long a guest stays signed in, in hours; 0 means until the host stops sharing. */
    var sessionHours: Int
        get() = sp.getInt("session_h", 6)
        set(v) = sp.edit().putInt("session_h", v).apply()

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) = sp.edit().putString("password", v).apply()

    var maxClients: Int
        get() = sp.getInt("max", 5)
        set(v) = sp.edit().putInt("max", v).apply()

    var port: Int
        get() = sp.getInt("port", 8080)
        set(v) = sp.edit().putInt("port", v).apply()

    var gameEnabled: Boolean
        get() = sp.getBoolean("game", true)
        set(v) = sp.edit().putBoolean("game", v).apply()

    fun loadBlocks(): List<ServerConfig.Block> =
        (sp.getString("blocks", "") ?: "").lines().mapNotNull {
            val p = it.split('\t')
            if (p.size == 4) ServerConfig.Block(p[0], p[1], p[2], p[3]) else null
        }

    fun saveBlocks(list: List<ServerConfig.Block>) {
        sp.edit().putString("blocks", list.joinToString("\n") { "${it.id}\t${it.name}\t${it.ip}\t${it.device}" }).apply()
    }
}
