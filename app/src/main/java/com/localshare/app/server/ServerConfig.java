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
