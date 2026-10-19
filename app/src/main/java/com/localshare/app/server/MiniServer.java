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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Tiny dependency-free HTTP/1.1 + WebSocket server (JDK sockets only, Java 8 API level)
 * so it behaves the same on every Android version from 5.0 up.
 */
public final class MiniServer {
    public static final class SessionInfo {
        public final String id, name, ip;
        public final long since, lastSeen;

        SessionInfo(Session s) {
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
        final Map<String, String> q = new HashMap<String, String>();
        final Map<String, String> h = new HashMap<String, String>();
        final Map<String, String> form = new HashMap<String, String>();
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final long ACTIVE_MS = 30000L;        // a device "occupies a slot" while it pings within 30s
    private static final long EXPIRE_MS = 6L * 3600000L; // login stays valid 6h after last activity
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerConfig cfg;
    private volatile FileSource fs;
    private final AssetProvider assets;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<String, Session>();
    private final ConcurrentHashMap<String, long[]> fails = new ConcurrentHashMap<String, long[]>();
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

    // ---------------------------------------------------------------- lifecycle / host API

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
        game = new GameHub(cfg, pool);
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

    /** Secret the in-app browser uses to enter as host (accepted from loopback only). */
    public String hostToken() {
        return hostToken;
    }

    /** Devices currently connected (active in the last 30s), host excluded. */
    public List<SessionInfo> sessions() {
        long now = System.currentTimeMillis();
        List<SessionInfo> out = new ArrayList<SessionInfo>();
        for (Session s : sessions.values()) if (!s.host && now - s.lastSeen < ACTIVE_MS) out.add(new SessionInfo(s));
        Collections.sort(out, new Comparator<SessionInfo>() {
            @Override public int compare(SessionInfo a, SessionInfo b) {
                return a.since < b.since ? -1 : (a.since == b.since ? 0 : 1);
            }
        });
        return out;
    }

    public void kick(String id) {
        for (Session s : sessions.values()) if (s.id.equals(id)) drop(s);
    }

    public void kickAll() {
        for (Session s : sessions.values()) if (!s.host) drop(s);
    }

    /** Kick and block by IP address AND browser device-id, so rejoining from a new tab fails too. */
    public void block(String id) {
        Session t = null;
        for (Session s : sessions.values()) if (s.id.equals(id)) t = s;
        if (t == null || t.host) return;
        cfg.addBlock(new ServerConfig.Block(hex(6), t.name, t.ip, t.device));
        for (Session s : sessions.values()) {
            if (!s.host && (s.ip.equals(t.ip) || (t.device.length() > 0 && t.device.equals(s.device)))) drop(s);
        }
    }

    private void drop(Session s) {
        sessions.remove(s.token);
        if (game != null) game.drop(s.token);
    }

    // ---------------------------------------------------------------- accept / connection loop

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

    // ---------------------------------------------------------------- parsing

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

    // ---------------------------------------------------------------- responses

    private static String reason(int c) {
        switch (c) {
            case 200: return "OK";
            case 101: return "Switching Protocols";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            default: return "Error";
        }
    }

    private static void head(OutputStream out, int code, String ctype, long len, String... extra) throws IOException {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        if (ctype != null) h.append("Content-Type: ").append(ctype).append("\r\n");
        h.append("Content-Length: ").append(len).append("\r\nX-Content-Type-Options: nosniff\r\n");
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

    // ---------------------------------------------------------------- sessions

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
        if (System.currentTimeMillis() - s.lastSeen > EXPIRE_MS) {
            sessions.remove(t);
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

    private void login(Req r, OutputStream out, boolean isHead) throws IOException {
        String device = Util.cleanId(r.form.get("device"));
        String name = Util.cleanName(r.form.get("name"));
        boolean host = false;
        String ht = r.form.get("hostToken");
        if (ht != null && ht.length() > 0) {
            if (!isLoopback(r.ip) || !Util.safeEq(ht, hostToken)) {
                json(out, isHead, 403, err("bad host token"));
                return;
            }
            host = true;
        }
        long now = System.currentTimeMillis();
        if (!host) {
            if (cfg.isBlocked(r.ip, device)) {
                json(out, isHead, 403, err("blocked"));
                return;
            }
            long[] f = fails.get(r.ip);
            if (f != null && f[1] > now) {
                json(out, isHead, 429, "{\"error\":\"Too many wrong passwords\",\"retry\":" + ((f[1] - now) / 1000 + 1) + "}");
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
                json(out, isHead, 401, err("Wrong password"));
                return;
            }
            fails.remove(r.ip);
            if (device.length() > 0) for (Session s : sessions.values()) if (!s.host && device.equals(s.device)) drop(s);
            if (activeCount(null) >= cfg.maxClients) {
                json(out, isHead, 429, "{\"error\":\"Room is full\",\"max\":" + cfg.maxClients + "}");
                return;
            }
        } else {
            for (Session s : sessions.values()) if (s.host) drop(s);
        }
        if (name.length() == 0) name = host ? "Host" : "Guest " + (100 + rnd.nextInt(900));
        String token = hex(16);
        Session s = new Session(token, hex(4), name, r.ip, device, host);
        sessions.put(token, s);
        json(out, isHead, 200, "{\"ok\":true,\"name\":" + Util.q(name) + ",\"host\":" + host + "}",
                "Set-Cookie: ls_token=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=21600");
    }

    // ---------------------------------------------------------------- routing

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
            return asset(out, isHead, n, n.endsWith(".js") ? "application/javascript; charset=utf-8" : "application/octet-stream", "public, max-age=86400");
        }
        if (get && path.equals("/api/info")) {
            Session s = sessionOf(r);
            json(out, isHead, 200, "{\"needPassword\":" + (cfg.password.length() > 0) + ",\"gameEnabled\":" + cfg.gameEnabled
                    + ",\"max\":" + cfg.maxClients + ",\"active\":" + activeCount(null) + ",\"authed\":" + (s != null)
                    + ",\"name\":" + Util.q(s == null ? "" : s.name) + ",\"host\":" + (s != null && s.host) + "}");
            return true;
        }
        if (post && path.equals("/api/login")) {
            login(r, out, false);
            return true;
        }
        if (post && path.equals("/api/logout")) {
            Session s = sessionOf(r);
            if (s != null) drop(s);
            json(out, false, 200, "{\"ok\":true}", "Set-Cookie: ls_token=; Path=/; HttpOnly; Max-Age=0");
            return true;
        }
        if (get && path.equals("/api/ping")) {
            if (auth(r, out, isHead) == null) return true;
            json(out, isHead, 200, "{\"ok\":true}");
            return true;
        }
        if (get && path.equals("/api/list")) {
            if (auth(r, out, isHead) == null) return true;
            return list(r, out, isHead);
        }
        if (get && path.equals("/api/file")) {
            if (auth(r, out, isHead) == null) return true;
            return file(r, out, isHead);
        }
        if (get && path.equals("/ws")) {
            return websocket(r, sock, in, out);
        }
        json(out, isHead, 404, err("not found"));
        return true;
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

    /** Splits and validates a user path. Returns null when it tries to escape the folder. */
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
        StringBuilder sb = new StringBuilder("{\"path\":");
        StringBuilder p = new StringBuilder();
        for (String s : sg) p.append(p.length() > 0 ? "/" : "").append(s);
        sb.append(Util.q(p.toString())).append(",\"entries\":[");
        boolean first = true;
        for (FileSource.Entry e : es) {
            if (e.name.startsWith(".")) continue; // hide dotfiles
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"n\":").append(Util.q(e.name)).append(",\"d\":").append(e.dir ? 1 : 0)
                    .append(",\"s\":").append(e.size).append(",\"m\":").append(e.mtime)
                    .append(",\"k\":").append(Util.q(e.dir ? "dir" : Util.kind(e.name))).append('}');
        }
        sb.append("]}");
        json(out, isHead, 200, sb.toString());
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

    private boolean file(Req r, OutputStream out, boolean isHead) throws IOException {
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
        // never let shared HTML/SVG run as a page on our origin
        if (ext.equals("html") || ext.equals("htm") || ext.equals("svg") || ext.equals("xml")) mime = "text/plain; charset=utf-8";
        boolean dl = "1".equals(r.q.get("dl"));
        long size = e.size, start = 0, end = size - 1;
        boolean partial = false;
        String rg = r.h.get("range");
        if (rg != null && rg.startsWith("bytes=") && rg.indexOf(',') < 0) {
            String spec = rg.substring(6).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash == 0) { // suffix: last N bytes
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
        String disp = dl ? "attachment" : "inline";
        String enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        hdr.add("Content-Disposition: " + disp + "; filename=\"" + ascii(name) + "\"; filename*=UTF-8''" + enc);
        head(out, partial ? 206 : 200, dl ? "application/octet-stream" : mime, len, hdr.toArray(new String[0]));
        if (isHead || len == 0) return true;
        InputStream is;
        try {
            is = fs.open(sg, start);
        } catch (IOException ex) {
            return false; // headers already sent; closing is the only honest signal
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

    private boolean websocket(Req r, Socket sock, InputStream in, OutputStream out) throws IOException {
        String up = r.h.get("upgrade");
        String key = r.h.get("sec-websocket-key");
        if (up == null || !up.equalsIgnoreCase("websocket") || key == null) {
            json(out, false, 400, err("websocket expected"));
            return false;
        }
        // cookie auth + WebSocket => must reject cross-site pages (CSWSH)
        String origin = r.h.get("origin"), host = r.h.get("host");
        if (origin != null) {
            int i = origin.indexOf("//");
            if (host == null || i < 0 || !origin.substring(i + 2).equalsIgnoreCase(host)) {
                json(out, false, 403, err("origin"));
                return false;
            }
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
