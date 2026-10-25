package com.localshare.app.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Small dependency-free HTTP/1.1 and WebSocket server on plain JDK sockets and the Java 8 API,
 * so it behaves the same on every Android version from 5.0 up.
 */
public final class MiniServer {
    public static final class SessionInfo {
        public final String id, name, ip;
        public final long since, lastSeen;
        public final int perms;

        SessionInfo(Session s, int perms) {
            this.perms = perms;
            id = s.id;
            name = s.name;
            ip = s.ip;
            since = s.since;
            lastSeen = s.lastSeen;
        }
    }

    private static final class Session {
        final String token, id, name, ip, device;
        final boolean host;
        final long since = System.currentTimeMillis();
        volatile long lastSeen = since;

        Session(String token, String id, String name, String ip, String device, boolean host) {
            this.token = token;
            this.id = id;
            this.name = name;
            this.ip = ip;
            this.device = device;
            this.host = host;
        }
    }

    private static final class Req {
        String method, path, ip;
        long bodyLen = -1; // set instead of reading the body, for streamed uploads
        final Map<String, String> q = new HashMap<String, String>();
        final Map<String, String> h = new HashMap<String, String>();
        final Map<String, String> form = new HashMap<String, String>();
    }

    /** Byte-capped least-recently-used cache of generated thumbnails. */
    private static final class ThumbCache {
        private final long cap;
        private long size;
        private final LinkedHashMap<String, byte[]> map = new LinkedHashMap<String, byte[]>(64, 0.75f, true);

        ThumbCache(long cap) {
            this.cap = cap;
        }

        synchronized byte[] get(String k) {
            return map.get(k);
        }

        synchronized void put(String k, byte[] v) {
            if (v.length > cap / 4) return;
            byte[] old = map.put(k, v);
            if (old != null) size -= old.length;
            size += v.length;
            Iterator<Map.Entry<String, byte[]>> it = map.entrySet().iterator();
            while (size > cap && it.hasNext()) {
                size -= it.next().getValue().length;
                it.remove();
            }
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final long ACTIVE_MS = 30000L; // a device occupies a slot while it pings within 30s
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerConfig cfg;
    private volatile FileSource fs;
    private final AssetProvider assets;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<String, Session>();
    private final ConcurrentHashMap<String, long[]> fails = new ConcurrentHashMap<String, long[]>();
    private final ThumbCache thumbs = new ThumbCache(12L * 1024 * 1024);
    private final Semaphore thumbSlots = new Semaphore(3); // decoding photos is memory-hungry on low-end phones
    private final SecureRandom rnd = new SecureRandom();
    private final String hostToken = hex(16);
    private ServerSocket ss;
    private ThreadPoolExecutor pool;
    private GameHub game;
    private volatile boolean running;
    private int port;

    public MiniServer(ServerConfig cfg, FileSource fs, AssetProvider assets) {
        this.cfg = cfg;
        this.fs = fs;
        this.assets = assets;
    }

    public void setFileSource(FileSource f) {
        fs = f;
    }

    public synchronized void start(int port) throws IOException {
        if (running) return;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        ss = s;
        this.port = s.getLocalPort();
        pool = new ThreadPoolExecutor(4, 160, 30, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
        game = new GameHub(cfg, pool, true);
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                acceptLoop();
            }
        }, "accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (ss != null) ss.close();
        } catch (IOException ignored) {
        }
        if (game != null) game.shutdown();
        if (pool != null) pool.shutdownNow();
        sessions.clear();
    }

    public int port() {
        return port;
    }

    /** Secret the in-app browser uses to enter as host; accepted from loopback only. */
    public String hostToken() {
        return hostToken;
    }

    /** Devices active in the last 30 seconds, host excluded. */
    public List<SessionInfo> sessions() {
        long now = System.currentTimeMillis();
        List<SessionInfo> out = new ArrayList<SessionInfo>();
        for (Session s : sessions.values()) if (!s.host && now - s.lastSeen < ACTIVE_MS) out.add(new SessionInfo(s, cfg.perms(ServerConfig.permKey(s.device, s.ip))));
        Collections.sort(out, new Comparator<SessionInfo>() {
            @Override public int compare(SessionInfo a, SessionInfo b) {
                return a.since < b.since ? -1 : (a.since == b.since ? 0 : 1);
            }
        });
        return out;
    }

    public void kick(String id) {
        for (Session s : sessions.values()) {
            if (s.id.equals(id)) {
                cfg.log.add("kick", s.name, s.ip, "");
                drop(s);
            }
        }
    }

    public void kickAll() {
        for (Session s : sessions.values()) if (!s.host) drop(s);
    }

    /** Kicks and blocks by IP address and by browser device id, so a new tab can't slip back in. */
    public void block(String id) {
        Session t = null;
        for (Session s : sessions.values()) if (s.id.equals(id)) t = s;
        if (t == null || t.host) return;
        cfg.addBlock(new ServerConfig.Block(hex(6), t.name, t.ip, t.device));
        cfg.log.add("block", t.name, t.ip, "");
        for (Session s : sessions.values()) {
            if (!s.host && (s.ip.equals(t.ip) || (t.device.length() > 0 && t.device.equals(s.device)))) drop(s);
        }
    }

    private void drop(Session s) {
        sessions.remove(s.token);
        if (game != null) game.drop(s.token);
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = ss.accept();
                String ip = s.getInetAddress().getHostAddress();
                int pct = ip.indexOf('%');
                if (pct > 0) ip = ip.substring(0, pct);
                final String fip = ip;
                if (!isLoopback(fip) && cfg.isBlocked(fip, null)) {
                    s.close();
                    continue;
                }
                try {
                    pool.execute(new Runnable() {
                        @Override public void run() {
                            handle(s, fip);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    s.close();
                }
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket s, String ip) {
        try {
            s.setSoTimeout(20000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 8192);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 32768);
            while (running) {
                Req r = parse(in, ip);
                if (r == null) break;
                boolean keep = route(r, s, in, out);
                out.flush();
                if (!keep) break;
            }
        } catch (SocketTimeoutException ignored) {
        } catch (IOException ignored) {
        } catch (RuntimeException ignored) {
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static void parseForm(String s, Map<String, String> into) {
        if (s == null || s.length() == 0) return;
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), "UTF-8");
                String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                into.put(k, v);
            } catch (Exception ignored) {
            }
        }
    }

    private Req parse(InputStream in, String ip) throws IOException {
        String line = readLine(in);
        if (line == null || line.length() == 0) return null;
        String[] p = line.split(" ");
        if (p.length < 3) return null;
        Req r = new Req();
        r.ip = ip;
        r.method = p[0];
        String target = p[1];
        int qi = target.indexOf('?');
        String rawPath = qi < 0 ? target : target.substring(0, qi);
        try {
            r.path = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return null;
        }
        parseForm(qi < 0 ? "" : target.substring(qi + 1), r.q);
        String hl;
        int n = 0;
        while ((hl = readLine(in)) != null && hl.length() > 0) {
            if (++n > 64) throw new IOException("too many headers");
            int ci = hl.indexOf(':');
            if (ci > 0) r.h.put(hl.substring(0, ci).trim().toLowerCase(), hl.substring(ci + 1).trim());
        }
        String cl = r.h.get("content-length");
        if (cl != null) {
            int len;
            try {
                len = Integer.parseInt(cl);
            } catch (NumberFormatException e) {
                return null;
            }
            if (len >= 0 && r.method.equals("POST") && (r.path.equals("/api/upload") || r.path.equals("/api/save"))) {
                r.bodyLen = len;
                return r;
            }
            if (len < 0 || len > 4096) throw new IOException("body too large");
            byte[] b = new byte[len];
            int o = 0;
            while (o < len) {
                int k = in.read(b, o, len - o);
                if (k < 0) throw new IOException("eof");
                o += k;
            }
            parseForm(new String(b, UTF8), r.form);
        }
        return r;
    }

    private static String reason(int c) {
        switch (c) {
            case 200: return "OK";
            case 101: return "Switching Protocols";
            case 206: return "Partial Content";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    private static void head(OutputStream out, int code, String ctype, long len, String... extra) throws IOException {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        if (ctype != null) h.append("Content-Type: ").append(ctype).append("\r\n");
        h.append("Content-Length: ").append(len).append("\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: SAMEORIGIN\r\nReferrer-Policy: no-referrer\r\n");
        for (String e : extra) h.append(e).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(LATIN1));
    }

    private static void send(OutputStream out, boolean isHead, int code, String ctype, byte[] body, String... extra) throws IOException {
        head(out, code, ctype, body.length, extra);
        if (!isHead) out.write(body);
    }

    private static void json(OutputStream out, boolean isHead, int code, String body, String... extra) throws IOException {
        String[] e = new String[extra.length + 1];
        e[0] = "Cache-Control: no-store";
        System.arraycopy(extra, 0, e, 1, extra.length);
        send(out, isHead, code, "application/json; charset=utf-8", body.getBytes(UTF8), e);
    }

    private static String err(String msg) {
        return "{\"error\":" + Util.q(msg) + "}";
    }

    private static String cookie(Req r, String name) {
        String c = r.h.get("cookie");
        if (c == null) return null;
        for (String part : c.split(";")) {
            part = part.trim();
            if (part.startsWith(name + "=")) return part.substring(name.length() + 1);
        }
        return null;
    }

    private Session sessionOf(Req r) {
        String t = cookie(r, "ls_token");
        if (t == null) return null;
        Session s = sessions.get(t);
        if (s == null) return null;
        if (!s.host && cfg.sessionMs > 0 && System.currentTimeMillis() - s.since > cfg.sessionMs) {
            drop(s);
            return null;
        }
        return s;
    }

    private int activeCount(Session except) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Session s : sessions.values()) if (!s.host && s != except && now - s.lastSeen < ACTIVE_MS) n++;
        return n;
    }

    private int permsOf(Session s) {
        return s == null || s.host ? 0 : cfg.perms(ServerConfig.permKey(s.device, s.ip));
    }

    /** True when this guest may only play the game: the whole phone is in game-only mode, or this device was set to it. */
    private boolean gameOnly(Session s) {
        return s != null && !s.host && (cfg.gameOnly || (permsOf(s) & ServerConfig.P_GAME_ONLY) != 0);
    }

    private boolean canUpload(Session s) {
        return s != null && (s.host || (cfg.allowUploads && !gameOnly(s)));
    }

    private boolean canDelete(Session s) {
        return s != null && (s.host || (!gameOnly(s) && (cfg.allowDelete || (permsOf(s) & ServerConfig.P_DELETE) != 0)));
    }

    private boolean canRename(Session s) {
        return s != null && (s.host || (!gameOnly(s) && (permsOf(s) & ServerConfig.P_RENAME) != 0));
    }

    private boolean canEdit(Session s) {
        return s != null && (s.host || (!gameOnly(s) && (permsOf(s) & ServerConfig.P_EDIT) != 0));
    }

    /** Like auth, but also refuses guests who may only play the game. */
    private Session authFiles(Req r, OutputStream out, boolean isHead) throws IOException {
        Session s = auth(r, out, isHead);
        if (s != null && gameOnly(s)) {
            json(out, isHead, 403, err("This device can only play the game"));
            return null;
        }
        return s;
    }

    /** Host-side: change what one connected device may do. */
    public void setPerms(String id, int perms) {
        for (Session s : sessions.values()) {
            if (s.id.equals(id) && !s.host) {
                cfg.setPerms(ServerConfig.permKey(s.device, s.ip), perms);
                cfg.log.add("perms", s.name, s.ip, "");
            }
        }
    }

    /** Returns the session, or writes an error response and returns null. */
    private Session auth(Req r, OutputStream out, boolean isHead) throws IOException {
        Session s = sessionOf(r);
        if (s == null) {
            json(out, isHead, 401, err("auth"));
            return null;
        }
        if (!s.host) {
            if (cfg.isBlocked(s.ip, s.device) || cfg.isBlocked(r.ip, s.device)) {
                drop(s);
                json(out, isHead, 403, err("blocked"));
                return null;
            }
            long now = System.currentTimeMillis();
            if (now - s.lastSeen > ACTIVE_MS && activeCount(s) >= cfg.maxClients) {
                json(out, isHead, 429, err("full"));
                return null;
            }
        }
        s.lastSeen = System.currentTimeMillis();
        return s;
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private static boolean isLoopback(String ip) {
        return ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1");
    }

    /** Browsers always send Origin on POST and WebSocket; a mismatch means another site is driving the request. */
    private static boolean sameOrigin(Req r) {
        String origin = r.h.get("origin"), host = r.h.get("host");
        if (origin == null) return true;
        int i = origin.indexOf("//");
        return host != null && i >= 0 && origin.substring(i + 2).equalsIgnoreCase(host);
    }

    private void login(Req r, OutputStream out) throws IOException {
        String device = Util.cleanId(r.form.get("device"));
        String name = Util.cleanName(r.form.get("name"));
        boolean host = false;
        String ht = r.form.get("hostToken");
        if (ht != null && ht.length() > 0) {
            if (!isLoopback(r.ip) || !Util.safeEq(ht, hostToken)) {
                json(out, false, 403, err("bad host token"));
                return;
            }
            host = true;
        }
        long now = System.currentTimeMillis();
        if (!host) {
            if (cfg.isBlocked(r.ip, device)) {
                cfg.log.add("blocked", name, r.ip, "login refused");
                json(out, false, 403, err("blocked"));
                return;
            }
            long[] f = fails.get(r.ip);
            if (f != null && f[1] > now) {
                cfg.log.add("denied", name, r.ip, "locked out");
                json(out, false, 429, "{\"error\":\"Too many wrong passwords\",\"retry\":" + ((f[1] - now) / 1000 + 1) + "}");
                return;
            }
            String pw = cfg.password;
            if (pw.length() > 0 && !Util.safeEq(pw, r.form.get("password"))) {
                synchronized (fails) {
                    long[] g = fails.get(r.ip);
                    if (g == null) {
                        g = new long[2];
                        fails.put(r.ip, g);
                    }
                    if (g[1] != 0 && g[1] <= now) {
                        g[0] = 0;
                        g[1] = 0;
                    }
                    if (++g[0] >= 5) {
                        g[1] = now + 60000;
                        g[0] = 0;
                    }
                }
                cfg.log.add("denied", name, r.ip, "wrong password");
                json(out, false, 401, err("Wrong password"));
                return;
            }
            fails.remove(r.ip);
            if (device.length() > 0) for (Session s : sessions.values()) if (!s.host && device.equals(s.device)) drop(s);
            if (activeCount(null) >= cfg.maxClients) {
                cfg.log.add("full", name, r.ip, "room full");
                json(out, false, 429, "{\"error\":\"Room is full\",\"max\":" + cfg.maxClients + "}");
                return;
            }
        } else {
            for (Session s : sessions.values()) if (s.host) drop(s);
        }
        if (name.length() == 0) name = host ? "Host" : "Guest " + (100 + rnd.nextInt(900));
        if (!host) cfg.log.add("join", name, r.ip, "");
        String token = hex(16);
        Session s = new Session(token, hex(4), name, r.ip, device, host);
        sessions.put(token, s);
        json(out, false, 200, "{\"ok\":true,\"name\":" + Util.q(name) + ",\"host\":" + host + "}",
                "Set-Cookie: ls_token=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=86400");
    }

    /** @return true when the connection may be reused for another request */
    private boolean route(Req r, Socket sock, InputStream in, OutputStream out) throws IOException {
        boolean isHead = r.method.equals("HEAD");
        boolean get = r.method.equals("GET") || isHead;
        boolean post = r.method.equals("POST");
        if (!get && !post) {
            json(out, false, 405, err("method"));
            return false;
        }
        String path = r.path;

        if (get && (path.equals("/") || path.equals("/index.html"))) return asset(out, isHead, "index.html", "text/html; charset=utf-8", "no-cache");
        if (get && path.startsWith("/static/")) {
            String n = path.substring(8);
            return asset(out, isHead, n, staticType(n), n.endsWith(".html") ? "no-cache" : "public, max-age=86400");
        }
        if (get && path.equals("/api/info")) {
            Session s = sessionOf(r);
            json(out, isHead, 200, "{\"needPassword\":" + (cfg.password.length() > 0) + ",\"gameEnabled\":" + (cfg.gameEnabled || gameOnly(s))
                    + ",\"max\":" + cfg.maxClients + ",\"active\":" + activeCount(null) + ",\"authed\":" + (s != null)
                    + ",\"name\":" + Util.q(s == null ? "" : s.name) + ",\"host\":" + (s != null && s.host)
                    + ",\"canUpload\":" + canUpload(s) + ",\"canDelete\":" + canDelete(s) + ",\"canRename\":" + canRename(s)
                    + ",\"canEdit\":" + canEdit(s) + ",\"gameOnly\":" + gameOnly(s) + ",\"maxUpload\":" + cfg.maxUploadBytes + "}");
            return true;
        }
        if (post && path.equals("/api/login")) {
            login(r, out);
            return true;
        }
        if (post && path.equals("/api/logout")) {
            Session s = sessionOf(r);
            if (s != null) drop(s);
            json(out, false, 200, "{\"ok\":true}", "Set-Cookie: ls_token=; Path=/; HttpOnly; Max-Age=0");
            return true;
        }
        if (get && path.equals("/api/ping")) {
            Session s = auth(r, out, isHead);
            if (s == null) return true;
            json(out, isHead, 200, "{\"ok\":true,\"up\":" + canUpload(s) + ",\"del\":" + canDelete(s) + ",\"ren\":" + canRename(s)
                    + ",\"edt\":" + canEdit(s) + ",\"go\":" + gameOnly(s) + "}");
            return true;
        }
        if (get && path.equals("/api/list")) {
            if (authFiles(r, out, isHead) == null) return true;
            return list(r, out, isHead);
        }
        if (get && path.equals("/api/file")) {
            Session s = authFiles(r, out, isHead);
            if (s == null) return true;
            return file(r, s, out, isHead);
        }
        if (get && path.equals("/api/thumb")) {
            if (authFiles(r, out, isHead) == null) return true;
            return thumb(r, out, isHead);
        }
        if (get && path.equals("/api/zip")) {
            Session s = authFiles(r, out, isHead);
            if (s == null) return true;
            return zip(r, s, out);
        }
        if (post && path.equals("/api/upload")) {
            Session s = authFiles(r, out, false);
            if (s == null) return false;
            return upload(r, s, in, out);
        }
        if (post && path.equals("/api/delete")) {
            Session s = authFiles(r, out, false);
            if (s == null) return true;
            return delete(r, s, out);
        }
        if (post && path.equals("/api/rename")) {
            Session s = authFiles(r, out, false);
            if (s == null) return true;
            return rename(r, s, out);
        }
        if (post && path.equals("/api/save")) {
            Session s = authFiles(r, out, false);
            if (s == null) return false;
            return save(r, s, in, out);
        }
        if (get && path.equals("/ws")) return websocket(r, sock, in, out);
        json(out, isHead, 404, err("not found"));
        return true;
    }

    private static String staticType(String n) {
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private boolean asset(OutputStream out, boolean isHead, String name, String ctype, String cache) throws IOException {
        byte[] d = name.matches("[A-Za-z0-9._-]+") ? assets.get(name) : null;
        if (d == null) {
            json(out, isHead, 404, err("not found"));
            return true;
        }
        send(out, isHead, 200, ctype, d, "Cache-Control: " + cache);
        return true;
    }

    /** Splits and validates a user path. Returns null when it tries to escape the shared folders. */
    private static List<String> segs(String p) {
        List<String> out = new ArrayList<String>();
        if (p == null) return out;
        for (String s : p.split("/")) {
            if (s.length() == 0) continue;
            if (s.equals(".") || s.equals("..") || s.indexOf('\\') >= 0 || s.indexOf('\0') >= 0) return null;
            out.add(s);
        }
        return out;
    }

    private static String pathStr(List<String> sg) {
        StringBuilder b = new StringBuilder();
        for (String x : sg) b.append(b.length() > 0 ? "/" : "").append(x);
        return b.toString();
    }

    private static List<String> child(List<String> dir, String name) {
        List<String> p = new ArrayList<String>(dir);
        p.add(name);
        return p;
    }

    private boolean list(Req r, OutputStream out, boolean isHead) throws IOException {
        List<String> sg = segs(r.q.get("path"));
        if (sg == null) {
            json(out, isHead, 400, err("bad path"));
            return true;
        }
        List<FileSource.Entry> es;
        try {
            es = fs.list(sg);
        } catch (IOException e) {
            es = null;
        }
        if (es == null) {
            json(out, isHead, 404, err("not found"));
            return true;
        }
        StringBuilder sb = new StringBuilder("{\"path\":").append(Util.q(pathStr(sg))).append(",\"entries\":[");
        boolean first = true;
        for (FileSource.Entry e : es) {
            if (e.name.startsWith(".")) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"n\":").append(Util.q(e.name)).append(",\"d\":").append(e.dir ? 1 : 0)
                    .append(",\"s\":").append(e.size).append(",\"m\":").append(e.mtime)
                    .append(",\"k\":").append(Util.q(e.dir ? "dir" : Util.kind(e.name))).append('}');
        }
        json(out, isHead, 200, sb.append("]}").toString());
        return true;
    }

    private static String ascii(String n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            b.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? c : '_');
        }
        return b.toString();
    }

    private boolean file(Req r, Session s, OutputStream out, boolean isHead) throws IOException {
        List<String> sg = segs(r.q.get("path"));
        if (sg == null || sg.isEmpty()) {
            json(out, isHead, 400, err("bad path"));
            return true;
        }
        FileSource.Entry e;
        try {
            e = fs.stat(sg);
        } catch (IOException ex) {
            e = null;
        }
        if (e == null || e.dir) {
            json(out, isHead, 404, err("not found"));
            return true;
        }
        String name = e.name;
        String ext = Util.ext(name);
        String mime = Util.mime(name);
        // shared pages must never run as a page on this origin
        if (ext.equals("html") || ext.equals("htm") || ext.equals("svg") || ext.equals("xml")) mime = "text/plain; charset=utf-8";
        boolean dl = "1".equals(r.q.get("dl"));
        long size = e.size, start = 0, end = size - 1;
        boolean partial = false;
        String rg = r.h.get("range");
        if (rg != null && rg.startsWith("bytes=") && rg.indexOf(',') < 0) {
            String spec = rg.substring(6).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash == 0) {
                    long n = Long.parseLong(spec.substring(1));
                    start = Math.max(0, size - n);
                } else if (dash > 0) {
                    start = Long.parseLong(spec.substring(0, dash));
                    if (dash < spec.length() - 1) end = Math.min(end, Long.parseLong(spec.substring(dash + 1)));
                }
                partial = true;
            } catch (NumberFormatException ex) {
                partial = false;
                start = 0;
                end = size - 1;
            }
            if (partial && (start > end || start >= size)) {
                head(out, 416, null, 0, "Content-Range: bytes */" + size);
                return true;
            }
        }
        long len = size == 0 ? 0 : end - start + 1;
        List<String> hdr = new ArrayList<String>();
        hdr.add("Accept-Ranges: bytes");
        hdr.add("Cache-Control: private, max-age=60");
        if (partial) hdr.add("Content-Range: bytes " + start + "-" + end + "/" + size);
        String enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        hdr.add("Content-Disposition: " + (dl ? "attachment" : "inline") + "; filename=\"" + ascii(name) + "\"; filename*=UTF-8''" + enc);
        if (!s.host && !isHead && (!partial || start == 0)) cfg.log.add(dl ? "download" : "view", s.name, s.ip, pathStr(sg));
        head(out, partial ? 206 : 200, dl ? "application/octet-stream" : mime, len, hdr.toArray(new String[0]));
        if (isHead || len == 0) return true;
        InputStream is;
        try {
            is = fs.open(sg, start);
        } catch (IOException ex) {
            return false; // headers are already sent; closing the connection is the only honest signal
        }
        try {
            byte[] buf = new byte[65536];
            long left = len;
            while (left > 0) {
                int n = is.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
            return left == 0;
        } finally {
            try {
                is.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean thumb(Req r, OutputStream out, boolean isHead) throws IOException {
        List<String> sg = segs(r.q.get("path"));
        if (sg == null || sg.isEmpty()) {
            json(out, isHead, 400, err("bad path"));
            return true;
        }
        FileSource.Entry e;
        try {
            e = fs.stat(sg);
        } catch (IOException ex) {
            e = null;
        }
        String kind = e == null ? "" : Util.kind(e.name);
        if (e == null || e.dir || !(kind.equals("image") || kind.equals("video") || kind.equals("pdf"))) {
            json(out, isHead, 404, err("no thumbnail"));
            return true;
        }
        int w = 320;
        try {
            w = Integer.parseInt(r.q.get("w"));
        } catch (RuntimeException ignored) {
        }
        w = Math.max(96, Math.min(640, w));
        String etag = "\"" + e.mtime + "-" + e.size + "-" + w + "\"";
        String cache = "Cache-Control: private, max-age=86400";
        if (etag.equals(r.h.get("if-none-match"))) {
            head(out, 304, null, 0, "ETag: " + etag, cache);
            return true;
        }
        String key = pathStr(sg) + "|" + w + "|" + e.mtime + "|" + e.size;
        byte[] jpg = thumbs.get(key);
        if (jpg == null) {
            try {
                if (!thumbSlots.tryAcquire(8, TimeUnit.SECONDS)) {
                    json(out, isHead, 503, err("busy"));
                    return true;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                jpg = fs.thumbnail(sg, w);
            } catch (IOException ex) {
                jpg = null;
            } catch (RuntimeException ex) {
                jpg = null;
            } finally {
                thumbSlots.release();
            }
            if (jpg != null) thumbs.put(key, jpg);
        }
        if (jpg == null) {
            json(out, isHead, 404, err("no thumbnail"));
            return true;
        }
        send(out, isHead, 200, "image/jpeg", jpg, "ETag: " + etag, cache);
        return true;
    }

    private boolean zip(Req r, Session s, OutputStream out) throws IOException {
        List<String> sg = segs(r.q.get("path"));
        if (sg == null) {
            json(out, false, 400, err("bad path"));
            return true;
        }
        FileSource.Entry e;
        try {
            e = fs.stat(sg);
        } catch (IOException ex) {
            e = null;
        }
        if (e == null || !e.dir) {
            json(out, false, 404, err("not found"));
            return true;
        }
        String fname = (sg.isEmpty() ? "share" : sg.get(sg.size() - 1)) + ".zip";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        // no Content-Length: the archive is streamed, and closing the connection marks the end
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename=\""
                + ascii(fname) + "\"; filename*=UTF-8''" + enc
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").getBytes(LATIN1));
        if (!s.host) cfg.log.add("zip", s.name, s.ip, sg.isEmpty() ? "(all folders)" : pathStr(sg));
        ZipOutputStream zo = new ZipOutputStream(out);
        zo.setLevel(1); // phones compress slowly and most media is already compressed
        try {
            addDir(zo, sg, "", 0, new int[1]);
            zo.finish();
            out.flush();
        } catch (IOException ignored) {
            // the guest cancelled the download
        }
        return false;
    }

    private void addDir(ZipOutputStream zo, List<String> dir, String prefix, int depth, int[] count) throws IOException {
        if (depth > 20) return;
        List<FileSource.Entry> es = fs.list(dir);
        if (es == null) return;
        byte[] buf = new byte[65536];
        for (FileSource.Entry e : es) {
            if (e.name.startsWith(".") || count[0] >= 20000) continue;
            List<String> p = child(dir, e.name);
            if (e.dir) {
                addDir(zo, p, prefix + e.name + "/", depth + 1, count);
                continue;
            }
            InputStream is;
            try {
                is = fs.open(p, 0);
            } catch (IOException ex) {
                continue;
            }
            count[0]++;
            try {
                ZipEntry ze = new ZipEntry(prefix + e.name);
                if (e.mtime > 0) ze.setTime(e.mtime);
                zo.putNextEntry(ze);
                int n;
                while ((n = is.read(buf)) > 0) zo.write(buf, 0, n);
                zo.closeEntry();
            } finally {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String cleanFileName(String n) {
        if (n == null) return null;
        n = n.trim();
        if (n.length() == 0 || n.length() > 150 || n.startsWith(".")) return null;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '/' || c == '\\') return null;
        }
        return n;
    }

    private static String withSuffix(String name, int i) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) + " (" + i + ")" + name.substring(dot) : name + " (" + i + ")";
    }

    /** Returns true only when the request body was fully read, so the connection can be reused. */
    private boolean upload(Req r, Session s, InputStream in, OutputStream out) throws IOException {
        if (!sameOrigin(r)) {
            json(out, false, 403, err("origin"));
            return false;
        }
        if (!canUpload(s)) {
            json(out, false, 403, err("Uploads are turned off by the host"));
            return false;
        }
        List<String> dir = segs(r.q.get("path"));
        String name = cleanFileName(r.q.get("name"));
        if (dir == null || name == null) {
            json(out, false, 400, err("Bad file name"));
            return false;
        }
        if (dir.isEmpty()) {
            json(out, false, 400, err("Open a folder first"));
            return false;
        }
        if (r.bodyLen < 0) {
            json(out, false, 400, err("Length required"));
            return false;
        }
        long len = r.bodyLen;
        if (len > cfg.maxUploadBytes) {
            json(out, false, 413, err("File is too large"));
            return false;
        }
        FileSource.Entry d;
        try {
            d = fs.stat(dir);
        } catch (IOException ex) {
            d = null;
        }
        if (d == null || !d.dir) {
            json(out, false, 404, err("Folder not found"));
            return false;
        }
        String stored = name;
        for (int i = 1; fs.stat(child(dir, stored)) != null; i++) {
            if (i > 99) {
                json(out, false, 409, err("Too many files with that name"));
                return false;
            }
            stored = withSuffix(name, i);
        }
        OutputStream os;
        try {
            os = fs.create(dir, stored);
        } catch (IOException ex) {
            json(out, false, 500, err("Cannot write to this folder"));
            return false;
        }
        boolean complete = false;
        try {
            byte[] buf = new byte[65536];
            long left = len;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                os.write(buf, 0, n);
                left -= n;
            }
            complete = left == 0;
        } finally {
            try {
                os.close();
            } catch (IOException ignored) {
            }
            if (!complete) {
                try {
                    fs.delete(child(dir, stored));
                } catch (IOException ignored) {
                }
            }
        }
        if (!complete) return false;
        if (!s.host) cfg.log.add("upload", s.name, s.ip, pathStr(child(dir, stored)));
        json(out, false, 200, "{\"ok\":true,\"name\":" + Util.q(stored) + ",\"size\":" + len + "}");
        return true;
    }

    private boolean rename(Req r, Session s, OutputStream out) throws IOException {
        if (!sameOrigin(r)) {
            json(out, false, 403, err("origin"));
            return true;
        }
        if (!canRename(s)) {
            json(out, false, 403, err("Renaming is not allowed for this device"));
            return true;
        }
        List<String> sg = segs(r.form.get("path"));
        String name = cleanFileName(r.form.get("name"));
        if (sg == null || sg.size() < 2 || name == null) {
            json(out, false, 400, err("Bad file name"));
            return true;
        }
        FileSource.Entry e;
        try {
            e = fs.stat(sg);
        } catch (IOException ex) {
            e = null;
        }
        if (e == null || e.dir) {
            json(out, false, e == null ? 404 : 400, err(e == null ? "not found" : "Only files can be renamed"));
            return true;
        }
        List<String> to = new ArrayList<String>(sg.subList(0, sg.size() - 1));
        to.add(name);
        boolean ok;
        try {
            if (name.equals(e.name)) {
                json(out, false, 200, "{\"ok\":true,\"name\":" + Util.q(name) + "}");
                return true;
            }
            if (fs.stat(to) != null) {
                json(out, false, 409, err("A file with that name already exists"));
                return true;
            }
            ok = fs.rename(sg, name);
        } catch (IOException ex) {
            ok = false;
        }
        if (!ok) {
            json(out, false, 500, err("Could not rename this file"));
            return true;
        }
        if (!s.host) cfg.log.add("rename", s.name, s.ip, pathStr(sg) + " -> " + name);
        json(out, false, 200, "{\"ok\":true,\"name\":" + Util.q(name) + "}");
        return true;
    }

    private static final int MAX_EDIT_BYTES = 512 * 1024;

    /** Replaces the content of a small text file with the request body. */
    private boolean save(Req r, Session s, InputStream in, OutputStream out) throws IOException {
        if (!sameOrigin(r)) {
            json(out, false, 403, err("origin"));
            return false;
        }
        if (!canEdit(s)) {
            json(out, false, 403, err("Editing is not allowed for this device"));
            return false;
        }
        List<String> sg = segs(r.q.get("path"));
        if (sg == null || sg.size() < 2 || r.bodyLen < 0) {
            json(out, false, 400, err("bad request"));
            return false;
        }
        if (r.bodyLen > MAX_EDIT_BYTES) {
            json(out, false, 413, err("Text files up to 512 KB can be edited"));
            return false;
        }
        FileSource.Entry e;
        try {
            e = fs.stat(sg);
        } catch (IOException ex) {
            e = null;
        }
        if (e == null || e.dir || !"text".equals(Util.kind(e.name))) {
            json(out, false, e == null ? 404 : 400, err(e == null ? "not found" : "Only text files can be edited"));
            return false;
        }
        byte[] b = new byte[(int) r.bodyLen];
        int o = 0;
        while (o < b.length) {
            int k = in.read(b, o, b.length - o);
            if (k < 0) {
                json(out, false, 400, err("incomplete"));
                return false;
            }
            o += k;
        }
        try {
            OutputStream os = fs.overwrite(sg);
            try {
                os.write(b);
            } finally {
                os.close();
            }
        } catch (IOException ex) {
            json(out, false, 500, err("Could not save this file"));
            return true;
        }
        if (!s.host) cfg.log.add("edit", s.name, s.ip, pathStr(sg));
        json(out, false, 200, "{\"ok\":true,\"size\":" + b.length + "}");
        return true;
    }

    private boolean delete(Req r, Session s, OutputStream out) throws IOException {
        if (!sameOrigin(r)) {
            json(out, false, 403, err("origin"));
            return true;
        }
        if (!canDelete(s)) {
            json(out, false, 403, err("Deleting is turned off by the host"));
            return true;
        }
        String p = r.form.get("path");
        List<String> sg = segs(p == null ? r.q.get("path") : p);
        if (sg == null || sg.isEmpty()) {
            json(out, false, 400, err("bad path"));
            return true;
        }
        FileSource.Entry e;
        try {
            e = fs.stat(sg);
        } catch (IOException ex) {
            e = null;
        }
        if (e == null) {
            json(out, false, 404, err("not found"));
            return true;
        }
        if (e.dir) {
            json(out, false, 400, err("Only files can be deleted"));
            return true;
        }
        boolean ok;
        try {
            ok = fs.delete(sg);
        } catch (IOException ex) {
            ok = false;
        }
        if (!ok) {
            json(out, false, 500, err("Could not delete this file"));
            return true;
        }
        cfg.log.add("delete", s.name, s.ip, pathStr(sg));
        json(out, false, 200, "{\"ok\":true}");
        return true;
    }

    private boolean websocket(Req r, Socket sock, InputStream in, OutputStream out) throws IOException {
        String up = r.h.get("upgrade");
        String key = r.h.get("sec-websocket-key");
        if (up == null || !up.equalsIgnoreCase("websocket") || key == null) {
            json(out, false, 400, err("websocket expected"));
            return false;
        }
        // the cookie authenticates the socket, so another site's page must not be able to open it
        if (!sameOrigin(r)) {
            json(out, false, 403, err("origin"));
            return false;
        }
        Session s = auth(r, out, false);
        if (s == null) return false;
        String acc;
        try {
            acc = Util.b64(MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(LATIN1)));
        } catch (Exception e) {
            throw new IOException(e);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + "\r\n\r\n").getBytes(LATIN1));
        out.flush();
        game.run(s.token, s.id, s.name, sock, in, out);
        return false;
    }
}
