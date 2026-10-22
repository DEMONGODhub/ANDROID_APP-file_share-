package com.localshare.app.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Bounded in-memory record of what guests did. */
public final class ActivityLog {
    public static final class Entry {
        public final long time;
        public final String kind, who, ip, detail;

        Entry(long time, String kind, String who, String ip, String detail) {
            this.time = time;
            this.kind = kind;
            this.who = who;
            this.ip = ip;
            this.detail = detail;
        }
    }

    private static final int MAX = 300;
    private static final long DUPLICATE_WINDOW_MS = 5000;
    private final ArrayDeque<Entry> entries = new ArrayDeque<Entry>();

    synchronized void add(String kind, String who, String ip, String detail) {
        long now = System.currentTimeMillis();
        Entry last = entries.peekLast();
        // browsers re-request the same URL in bursts; collapsing them keeps the list readable
        if (last != null && now - last.time < DUPLICATE_WINDOW_MS && last.kind.equals(kind)
                && last.ip.equals(ip) && last.who.equals(who) && last.detail.equals(detail)) return;
        if (entries.size() >= MAX) entries.removeFirst();
        entries.addLast(new Entry(now, kind, who == null ? "" : who, ip == null ? "" : ip, detail == null ? "" : detail));
    }

    /** Newest first. */
    public synchronized List<Entry> recent() {
        List<Entry> out = new ArrayList<Entry>(entries.size());
        Iterator<Entry> it = entries.descendingIterator();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    public synchronized void clear() {
        entries.clear();
    }
}
