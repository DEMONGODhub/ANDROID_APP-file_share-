package com.localshare.app.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** The shared content. Paths are lists of already-validated segments. */
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

    /** Children of a directory, or null if the path is missing or not a directory. */
    List<Entry> list(List<String> path) throws IOException;

    /** Info for a path (the root has an empty name), or null if missing. */
    Entry stat(List<String> path) throws IOException;

    InputStream open(List<String> path, long offset) throws IOException;

    /** Creates a new file in {@code dir}. Fails if the name is taken. */
    OutputStream create(List<String> dir, String name) throws IOException;

    /** Deletes a file. Folders are never deleted. */
    boolean delete(List<String> path) throws IOException;

    /** Renames a file inside its folder. Folders are never renamed. {@code newName} is a plain, already-validated name. */
    boolean rename(List<String> path, String newName) throws IOException;

    /** Opens an existing file for writing, replacing its content. */
    OutputStream overwrite(List<String> path) throws IOException;

    /** Small JPEG preview of an image or video no larger than maxPx, or null when none can be made. */
    byte[] thumbnail(List<String> path, int maxPx) throws IOException;
}
