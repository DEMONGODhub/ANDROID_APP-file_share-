package com.localshare.app.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Plain java.io folder source (used by the desktop test harness). */
public final class DirFileSource implements FileSource {
    private final File root;
    private final String rootCanon;

    public DirFileSource(File root) throws IOException {
        this.root = root;
        this.rootCanon = root.getCanonicalPath();
    }

    private File resolve(List<String> path) throws IOException {
        File f = root;
        for (String s : path) f = new File(f, s);
        String c = f.getCanonicalPath();
        // block symlink escapes
        if (!c.equals(rootCanon) && !c.startsWith(rootCanon + File.separator)) return null;
        return f;
    }

    @Override public List<Entry> list(List<String> path) throws IOException {
        File f = resolve(path);
        if (f == null || !f.isDirectory()) return null;
        File[] kids = f.listFiles();
        List<Entry> out = new ArrayList<Entry>();
        if (kids != null) for (File k : kids) out.add(new Entry(k.getName(), k.isDirectory(), k.length(), k.lastModified()));
        return out;
    }

    @Override public Entry stat(List<String> path) throws IOException {
        File f = resolve(path);
        if (f == null || !f.exists()) return null;
        return new Entry(path.isEmpty() ? "" : f.getName(), f.isDirectory(), f.length(), f.lastModified());
    }

    @Override public InputStream open(List<String> path, long offset) throws IOException {
        File f = resolve(path);
        if (f == null || !f.isFile()) throw new IOException("not a file");
        FileInputStream in = new FileInputStream(f);
        if (offset > 0) in.getChannel().position(offset);
        return in;
    }
}
