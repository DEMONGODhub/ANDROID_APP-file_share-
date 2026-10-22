package com.localshare.app.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Several shared folders presented as one tree: the root lists the folders, visitors pick one. */
public final class CompositeFileSource implements FileSource {
    private final LinkedHashMap<String, FileSource> mounts = new LinkedHashMap<String, FileSource>();

    public CompositeFileSource(Map<String, FileSource> sources) {
        mounts.putAll(sources);
    }

    /** A safe, unique URL segment for a folder's display name. */
    public static String mountName(String raw, Set<String> used) {
        String n = raw == null ? "" : raw.replace('/', '_').replace('\\', '_').trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.length() == 0) n = "Folder";
        if (n.length() > 60) n = n.substring(0, 60).trim();
        String out = n;
        for (int i = 2; used.contains(out); i++) out = n + " (" + i + ")";
        used.add(out);
        return out;
    }

    private FileSource mount(List<String> path) {
        return path.isEmpty() ? null : mounts.get(path.get(0));
    }

    private static List<String> rest(List<String> path) {
        return new ArrayList<String>(path.subList(1, path.size()));
    }

    @Override public List<Entry> list(List<String> path) throws IOException {
        if (path.isEmpty()) {
            List<Entry> out = new ArrayList<Entry>();
            for (String n : mounts.keySet()) out.add(new Entry(n, true, 0, 0));
            return out;
        }
        FileSource m = mount(path);
        return m == null ? null : m.list(rest(path));
    }

    @Override public Entry stat(List<String> path) throws IOException {
        if (path.isEmpty()) return new Entry("", true, 0, 0);
        FileSource m = mount(path);
        if (m == null) return null;
        Entry e = m.stat(rest(path));
        if (e == null) return null;
        return path.size() == 1 ? new Entry(path.get(0), true, 0, e.mtime) : e;
    }

    @Override public InputStream open(List<String> path, long offset) throws IOException {
        FileSource m = mount(path);
        if (m == null || path.size() < 2) throw new IOException("not a file");
        return m.open(rest(path), offset);
    }

    @Override public OutputStream create(List<String> dir, String name) throws IOException {
        FileSource m = mount(dir);
        if (m == null) throw new IOException("open a folder first");
        return m.create(rest(dir), name);
    }

    @Override public boolean delete(List<String> path) throws IOException {
        FileSource m = mount(path);
        return m != null && path.size() > 1 && m.delete(rest(path));
    }

    @Override public byte[] thumbnail(List<String> path, int maxPx) throws IOException {
        FileSource m = mount(path);
        return m == null || path.size() < 2 ? null : m.thumbnail(rest(path), maxPx);
    }
}
