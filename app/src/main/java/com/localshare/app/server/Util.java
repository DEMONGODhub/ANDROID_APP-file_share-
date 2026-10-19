package com.localshare.app.server;

import java.util.HashMap;
import java.util.Map;

final class Util {
    private Util() {}

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Base64 without java.util.Base64 (needs API 26). */
    static String b64(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int n = ((d[i] & 0xff) << 16) | (i + 1 < d.length ? (d[i + 1] & 0xff) << 8 : 0) | (i + 2 < d.length ? (d[i + 2] & 0xff) : 0);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]);
            sb.append(i + 1 < d.length ? B64[(n >> 6) & 63] : '=');
            sb.append(i + 2 < d.length ? B64[n & 63] : '=');
        }
        return sb.toString();
    }

    /** JSON string literal. */
    static String q(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** Display name: no control chars / markup-ish chars, max 20 chars. */
    static String cleanName(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 20; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7f && c != '<' && c != '>' && c != '"' && c != '\\') b.append(c);
        }
        return b.toString().trim();
    }

    /** Device id: [A-Za-z0-9-] only, max 40 chars. */
    static String cleanId(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 40; i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-') b.append(c);
        }
        return b.toString();
    }

    /** Constant-time string compare. */
    static boolean safeEq(String a, String b) {
        if (a == null || b == null) return false;
        int d = a.length() ^ b.length();
        for (int i = 0; i < a.length() && i < b.length(); i++) d |= a.charAt(i) ^ b.charAt(i);
        return d == 0;
    }

    private static final Map<String, String> MIME = new HashMap<String, String>();
    private static final Map<String, String> KIND = new HashMap<String, String>();

    private static void m(String kind, String mime, String exts) {
        for (String e : exts.split(" ")) {
            MIME.put(e, mime == null ? "application/octet-stream" : mime);
            if (kind != null) KIND.put(e, kind);
        }
    }

    static {
        m("image", "image/jpeg", "jpg jpeg jpe");
        m("image", "image/png", "png");
        m("image", "image/gif", "gif");
        m("image", "image/webp", "webp");
        m("image", "image/bmp", "bmp");
        m("image", "image/avif", "avif");
        m("image", "image/svg+xml", "svg");
        m("image", "image/x-icon", "ico");
        m("video", "video/mp4", "mp4 m4v");
        m("video", "video/webm", "webm");
        m("video", "video/x-matroska", "mkv");
        m("video", "video/quicktime", "mov");
        m("video", "video/3gpp", "3gp");
        m("video", "video/x-msvideo", "avi");
        m("audio", "audio/mpeg", "mp3");
        m("audio", "audio/mp4", "m4a");
        m("audio", "audio/ogg", "ogg oga opus");
        m("audio", "audio/wav", "wav");
        m("audio", "audio/flac", "flac");
        m("audio", "audio/aac", "aac");
        m("pdf", "application/pdf", "pdf");
        m("text", "text/plain; charset=utf-8", "txt log md csv json xml yml yaml ini conf js ts css java kt py c cpp h cs go rs sh bat html htm php sql srt vtt");
        m(null, "application/zip", "zip");
        m(null, "application/vnd.android.package-archive", "apk");
        m(null, "application/x-7z-compressed", "7z");
        m(null, "application/x-rar-compressed", "rar");
    }

    static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    static String kind(String name) {
        String k = KIND.get(ext(name));
        return k == null ? "file" : k;
    }

    static String mime(String name) {
        String m = MIME.get(ext(name));
        return m == null ? "application/octet-stream" : m;
    }
}
