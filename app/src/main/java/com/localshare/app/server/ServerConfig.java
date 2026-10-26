package com.localshare.app.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Live, thread-safe settings and block list. Changes apply immediately to the running server. */
public final class ServerConfig {
    public static final class Block {
        public final String id, name, ip, device;

        public Block(String id, String name, String ip, String device) {
            this.id = id;
            this.name = name;
            this.ip = ip == null ? "" : ip;
            this.device = device == null ? "" : device;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Block)) return false;
            Block b = (Block) o;
            return id.equals(b.id) && name.equals(b.name) && ip.equals(b.ip) && device.equals(b.device);
        }

        @Override public int hashCode() {
            return id.hashCode();
        }
    }

    public volatile String password = "";
    public volatile int maxClients = 5;
    public volatile boolean gameEnabled = true;
    public volatile boolean allowUploads = false;
    public volatile boolean allowDelete = false;
    /** Game-only hosting: nobody but the host can see any file. */
    public volatile boolean gameOnly = false;
    /** 0 = PvP (endless, respawn), 1 = Battle Royale (rounds, shrinking zone). */
    public volatile int gameMode = 0;

    /** Switches the game mode live; the app saves it through {@link #onChange}. */
    public void setGameMode(int m) {
        int v = m == 1 ? 1 : 0;
        if (gameMode != v) {
            gameMode = v;
            changed();
        }
    }

    /** Per-device extras the host can grant (or restrict) from the Devices tab. */
    public static final int P_DELETE = 1, P_RENAME = 2, P_EDIT = 4, P_GAME_ONLY = 8, P_UPLOAD = 16, P_MOVE = 32, P_ALL = 63;
    private final ConcurrentHashMap<String, Integer> perms = new ConcurrentHashMap<String, Integer>();

    /** Key a device keeps across sign-ins: its browser id, or its IP address when it has none. */
    public static String permKey(String device, String ip) {
        return device != null && device.length() > 0 ? "d:" + device : "i:" + ip;
    }

    public int perms(String key) {
        Integer v = perms.get(key);
        return v == null ? 0 : v;
    }

    public void setPerms(String key, int v) {
        v &= P_ALL;
        if (v == 0) perms.remove(key); else perms.put(key, v);
        changed();
    }

    public java.util.Map<String, Integer> permsSnapshot() {
        return new java.util.HashMap<String, Integer>(perms);
    }

    public void loadPerms(java.util.Map<String, Integer> m) {
        perms.clear();
        for (java.util.Map.Entry<String, Integer> e : m.entrySet()) if ((e.getValue() & P_ALL) != 0) perms.put(e.getKey(), e.getValue() & P_ALL);
    }
    /** Guests sign in again this long after joining; 0 means never. */
    public volatile long sessionMs = 6L * 3600000L;
    public volatile long maxUploadBytes = 2L * 1024 * 1024 * 1024;
    public final ActivityLog log = new ActivityLog();
    /** Called from any thread whenever the block list changes. */
    public volatile Runnable onChange;

    private final ConcurrentHashMap<String, Block> blocks = new ConcurrentHashMap<String, Block>();

    public List<Block> blocks() {
        return new ArrayList<Block>(blocks.values());
    }

    public void setBlocks(List<Block> list) {
        blocks.clear();
        for (Block b : list) blocks.put(b.id, b);
    }

    public boolean isBlocked(String ip, String device) {
        for (Block b : blocks.values()) {
            if (b.ip.length() > 0 && b.ip.equals(ip)) return true;
            if (b.device.length() > 0 && device != null && b.device.equals(device)) return true;
        }
        return false;
    }

    void addBlock(Block b) {
        blocks.put(b.id, b);
        changed();
    }

    public void removeBlock(String id) {
        if (blocks.remove(id) != null) changed();
    }

    private void changed() {
        Runnable r = onChange;
        if (r != null) r.run();
    }
}
