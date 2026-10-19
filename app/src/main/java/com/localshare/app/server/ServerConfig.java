package com.localshare.app.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Live, thread-safe settings + block list. Changes apply immediately to the running server. */
public final class ServerConfig {
    public static final class Block {
        public final String id, name, ip, device;

        public Block(String id, String name, String ip, String device) {
            this.id = id;
            this.name = name;
            this.ip = ip == null ? "" : ip;
            this.device = device == null ? "" : device;
        }
    }

    public volatile String password = "";
    public volatile int maxClients = 5;
    public volatile boolean gameEnabled = true;
    /** Called (from any thread) whenever the block list changes. */
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
