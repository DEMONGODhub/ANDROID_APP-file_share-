package com.localshare.app.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Abstraction over the shared folder. Paths are lists of already-validated segments. */
public interface FileSource {
    final class Entry {
        public final String name;
        public final boolean dir;
        public final long size;
        public final long mtime;

        public Entry(String name, boolean dir, long size, long mtime) {
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.mtime = mtime;
        }
    }

    /** Children of a directory, or null if the path is missing / not a directory. */
    List<Entry> list(List<String> path) throws IOException;

    /** Info for a path (root has empty name), or null if missing. */
    Entry stat(List<String> path) throws IOException;

    /** Opens a file positioned at {@code offset}. */
    InputStream open(List<String> path, long offset) throws IOException;
}
