import com.localshare.app.server.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** End-to-end test of the server core over real HTTP. Usage: Harness test <web-assets-dir> | Harness serve <web-assets-dir> */
public class Harness {
    static int port;
    static int fail = 0, pass = 0;
    static File webDir;

    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
    }

    static class Res { int code; byte[] body; Map<String, List<String>> h; String text() { return new String(body); }
        String head(String k) { List<String> v = h.get(k); return v == null ? "" : v.get(0); } }

    static Res req(String method, String path, String cookie, String form, String... hdr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        if (cookie != null) c.setRequestProperty("Cookie", "ls_token=" + cookie);
        for (int i = 0; i + 1 < hdr.length; i += 2) c.setRequestProperty(hdr[i], hdr[i + 1]);
        if (form != null) { c.setDoOutput(true); c.getOutputStream().write(form.getBytes("UTF-8")); }
        return read(c);
    }

    static Res post(String path, String cookie, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod("POST");
        if (cookie != null) c.setRequestProperty("Cookie", "ls_token=" + cookie);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(body.length);
        OutputStream o = c.getOutputStream();
        o.write(body);
        o.flush();
        return read(c);
    }

    static Res read(HttpURLConnection c) throws Exception {
        Res r = new Res();
        r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); }
        r.body = bo.toByteArray();
        r.h = c.getHeaderFields();
        return r;
    }

    /** Raw request, so tests can send an Origin header that HttpURLConnection refuses to set. */
    static int raw(String method, String path, String cookie, String origin, String body) throws Exception {
        try (Socket k = new Socket("127.0.0.1", port)) {
            k.setSoTimeout(5000);
            byte[] b = body == null ? new byte[0] : body.getBytes("UTF-8");
            StringBuilder sb = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n");
            if (cookie != null) sb.append("Cookie: ls_token=").append(cookie).append("\r\n");
            if (origin != null) sb.append("Origin: ").append(origin).append("\r\n");
            if (method.equals("POST")) sb.append("Content-Length: ").append(b.length).append("\r\n");
            sb.append("Connection: close\r\n\r\n");
            OutputStream o = k.getOutputStream();
            o.write(sb.toString().getBytes("UTF-8"));
            o.write(b);
            o.flush();
            String line = new BufferedReader(new InputStreamReader(k.getInputStream())).readLine();
            return Integer.parseInt(line.split(" ")[1]);
        }
    }

    static String token(Res r) {
        List<String> sc = r.h.get("Set-Cookie");
        if (sc == null) return null;
        for (String s : sc) if (s.startsWith("ls_token=")) return s.substring(9, s.indexOf(';'));
        return null;
    }

    static String login(String dev, String pw, String name) throws Exception {
        Res r = req("POST", "/api/login", null, "device=" + dev + "&name=" + name + "&password=" + pw);
        return r.code == 200 ? token(r) : null;
    }

    static byte[] rnd(int n) { byte[] b = new byte[n]; new Random(n).nextBytes(b); return b; }

    static void write(File f, byte[] b) throws IOException { f.getParentFile().mkdirs(); Files.write(f.toPath(), b); }

    static AssetProvider assets() {
        return new AssetProvider() {
            public byte[] get(String n) throws IOException {
                File f = new File(webDir, n);
                return f.isFile() ? Files.readAllBytes(f.toPath()) : null;
            }
        };
    }

    /** Wraps a folder and counts thumbnail calls, standing in for Android's thumbnail provider. */
    static class ThumbSource implements FileSource {
        final FileSource in;
        int calls;
        ThumbSource(FileSource in) { this.in = in; }
        public List<Entry> list(List<String> p) throws IOException { return in.list(p); }
        public Entry stat(List<String> p) throws IOException { return in.stat(p); }
        public InputStream open(List<String> p, long o) throws IOException { return in.open(p, o); }
        public OutputStream create(List<String> d, String n) throws IOException { return in.create(d, n); }
        public boolean delete(List<String> p) throws IOException { return in.delete(p); }
        public boolean rename(List<String> p, String n) throws IOException { return in.rename(p, n); }
        public OutputStream overwrite(List<String> p) throws IOException { return in.overwrite(p); }
        public byte[] thumbnail(List<String> p, int px) { calls++; return new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) px}; }
    }

    static File[] fixtures() throws Exception {
        File root = Files.createTempDirectory("ls-test").toFile();
        File main = new File(root, "main"), extra = new File(root, "extra");
        write(new File(main, "movie.mp4"), rnd(3000000));
        write(new File(main, "docs/note.txt"), "hello world".getBytes("UTF-8"));
        write(new File(main, "docs/résumé.txt"), "cv".getBytes("UTF-8"));
        write(new File(main, ".secret"), "secret".getBytes("UTF-8"));
        write(new File(main, "page.html"), "<script>alert(1)</script>".getBytes("UTF-8"));
        write(new File(main, "photo.jpg"), rnd(5000));
        write(new File(extra, "readme.txt"), "extra".getBytes("UTF-8"));
        write(new File(extra, "tree/a.txt"), "alpha".getBytes("UTF-8"));
        write(new File(extra, "tree/sub/b.bin"), rnd(100000));
        write(new File(extra, "tree/sub/ünï.txt"), "unicode".getBytes("UTF-8"));
        write(new File(extra, "tree/.hidden"), "x".getBytes("UTF-8"));
        return new File[]{main, extra};
    }

    static CompositeFileSource composite(File main, File extra, boolean thumbs, ThumbSource[] hold) throws Exception {
        Map<String, FileSource> m = new LinkedHashMap<String, FileSource>();
        FileSource a = new DirFileSource(main), b = new DirFileSource(extra);
        if (thumbs) { hold[0] = new ThumbSource(a); a = hold[0]; }
        m.put("Main", a);
        m.put("Extra Files", b);
        return new CompositeFileSource(m);
    }

    static MiniServer start(ServerConfig cfg, FileSource fs) throws Exception {
        MiniServer s = new MiniServer(cfg, fs, assets());
        s.start(0);
        port = s.port();
        return s;
    }

    public static void main(String[] a) throws Exception {
        webDir = new File(a[1]);
        File[] fx = fixtures();
        if (a[0].equals("serve")) {
            ServerConfig cfg = new ServerConfig();
            cfg.allowUploads = true;
            cfg.allowDelete = true;
            cfg.maxClients = 10;
            cfg.allowDelete = false;
            cfg.setPerms(ServerConfig.permKey("uitest1", "127.0.0.1"), ServerConfig.P_RENAME | ServerConfig.P_EDIT);
            cfg.setPerms(ServerConfig.permKey("uitest2", "127.0.0.1"), ServerConfig.P_GAME_ONLY);
            MiniServer s = new MiniServer(cfg, composite(fx[0], fx[1], false, null), assets());
            s.start(18080);
            System.out.println("READY 18080 hostToken=" + s.hostToken());
            Thread.sleep(Long.MAX_VALUE);
        }
        checks(fx[0], fx[1]);
        community(fx[0], fx[1]);
        permissions(fx[0], fx[1]);
        System.out.println("\n== game rules");
        fail += ArenaTest.run();
        pass += ArenaTest.passCount();
        System.out.println("\nRESULT: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void checks(File main, File extra) throws Exception {
        ServerConfig cfg = new ServerConfig();
        cfg.password = "pw123";
        cfg.maxClients = 2;
        MiniServer s = start(cfg, composite(main, extra, false, null));

        System.out.println("== static + info");
        check("GET / serves index.html", req("GET", "/", null, null).text().contains("LocalShare"));
        check("pdf.js served as javascript", req("GET", "/static/pdf.min.js", null, null).head("Content-Type").startsWith("application/javascript"));
        Res pv = req("GET", "/static/pdfview.html", null, null);
        check("PDF page served as html", pv.code == 200 && pv.head("Content-Type").startsWith("text/html"));
        check("asset traversal blocked", req("GET", "/static/..%2f..%2fetc%2fpasswd", null, null).code == 404);
        check("info reports needPassword", req("GET", "/api/info", null, null).text().contains("\"needPassword\":true"));

        System.out.println("== auth");
        check("list without login -> 401", req("GET", "/api/list?path=", null, null).code == 401);
        check("file without login -> 401", req("GET", "/api/file?path=Main/movie.mp4", null, null).code == 401);
        check("thumb without login -> 401", req("GET", "/api/thumb?path=Main/photo.jpg", null, null).code == 401);
        check("delete without login -> 401", req("POST", "/api/delete", null, "path=Main/photo.jpg").code == 401);
        check("ws without login rejected", req("GET", "/ws", null, null, "Upgrade", "websocket", "Sec-WebSocket-Key", "x").code != 101);
        String t1 = login("dev1", "pw123", "Alice");
        check("correct password logs in", t1 != null);

        System.out.println("== folder chooser");
        Res root = req("GET", "/api/list?path=", t1, null);
        check("root lists the shared folders", root.code == 200 && root.text().contains("\"n\":\"Main\"") && root.text().contains("\"n\":\"Extra Files\"") && !root.text().contains("movie.mp4"));
        check("folder appears as a directory", root.text().contains("\"k\":\"dir\""));
        check("folder name with a space opens", req("GET", "/api/list?path=Extra%20Files", t1, null).text().contains("readme.txt"));
        check("unknown folder -> 404", req("GET", "/api/list?path=Nope", t1, null).code == 404);
        check("mount names are made safe + unique", mountNames());

        System.out.println("== files");
        Res l = req("GET", "/api/list?path=Main", t1, null);
        check("list a folder", l.code == 200 && l.text().contains("movie.mp4") && l.text().contains("docs") && l.text().contains("\"k\":\"video\""));
        check("dotfiles hidden", !l.text().contains(".secret"));
        check("unicode filename", req("GET", "/api/list?path=Main%2Fdocs", t1, null).text().contains("résumé.txt"));
        check("traversal ../ rejected", req("GET", "/api/list?path=..%2f..", t1, null).code == 400);
        check("traversal in file rejected", req("GET", "/api/file?path=Main%2Fdocs%2f..%2f..%2fetc%2fpasswd", t1, null).code == 400);
        check("absolute path stays inside", req("GET", "/api/file?path=%2Fetc%2Fpasswd", t1, null).code == 404);
        check("missing file 404", req("GET", "/api/file?path=Main%2Fnope.bin", t1, null).code == 404);
        byte[] orig = Files.readAllBytes(new File(main, "movie.mp4").toPath());
        Res full = req("GET", "/api/file?path=Main%2Fmovie.mp4", t1, null);
        check("full download identical (" + orig.length + " B)", full.code == 200 && Arrays.equals(full.body, orig));
        check("Accept-Ranges advertised", "bytes".equals(full.head("Accept-Ranges")));
        Res p = req("GET", "/api/file?path=Main%2Fmovie.mp4", t1, null, "Range", "bytes=1000-1999");
        check("range 206 + exact bytes", p.code == 206 && Arrays.equals(p.body, Arrays.copyOfRange(orig, 1000, 2000)));
        check("Content-Range header", p.head("Content-Range").equals("bytes 1000-1999/" + orig.length));
        check("open-ended range", Arrays.equals(req("GET", "/api/file?path=Main%2Fmovie.mp4", t1, null, "Range", "bytes=" + (orig.length - 500) + "-").body, Arrays.copyOfRange(orig, orig.length - 500, orig.length)));
        check("suffix range", Arrays.equals(req("GET", "/api/file?path=Main%2Fmovie.mp4", t1, null, "Range", "bytes=-100").body, Arrays.copyOfRange(orig, orig.length - 100, orig.length)));
        check("unsatisfiable range 416", req("GET", "/api/file?path=Main%2Fmovie.mp4", t1, null, "Range", "bytes=" + (orig.length + 10) + "-").code == 416);
        check("HEAD has no body", req("HEAD", "/api/file?path=Main%2Fmovie.mp4", t1, null).body.length == 0);
        check("shared HTML served as text/plain", req("GET", "/api/file?path=Main%2Fpage.html", t1, null).head("Content-Type").startsWith("text/plain"));
        check("dl=1 sets attachment", req("GET", "/api/file?path=Main%2Fdocs%2fnote.txt&dl=1", t1, null).head("Content-Disposition").startsWith("attachment"));
        check("image inline for the new-tab view", req("GET", "/api/file?path=Main%2Fphoto.jpg", t1, null).head("Content-Disposition").startsWith("inline"));

        System.out.println("== password brute force");
        for (int i = 0; i < 5; i++) check("wrong password #" + (i + 1) + " -> 401", req("POST", "/api/login", null, "device=evil&password=bad" + i).code == 401);
        check("locked out after 5 -> 429", req("POST", "/api/login", null, "device=evil&password=pw123").code == 429);
        s.stop();

        s = start(cfg, composite(main, extra, false, null));
        System.out.println("== device limit (max 2)");
        String a1 = login("devA", "pw123", "Alice"), b1 = login("devB", "pw123", "Bob");
        check("device 1 + 2 join", a1 != null && b1 != null);
        Res third = req("POST", "/api/login", null, "device=devC&name=Carl&password=pw123");
        check("3rd device rejected (full) -> 429", third.code == 429 && third.text().contains("full"));
        check("same device re-login does not use a 2nd slot", login("devA", "pw123", "Alice2") != null);
        check("host (loopback token) bypasses limit + password", req("POST", "/api/login", null, "hostToken=" + s.hostToken()).code == 200);
        check("bad host token rejected", req("POST", "/api/login", null, "hostToken=nope").code == 403);
        List<MiniServer.SessionInfo> ss = s.sessions();
        check("sessions() shows 2 guests, no host", ss.size() == 2);

        System.out.println("== kick + block");
        String bobId = null;
        for (MiniServer.SessionInfo i : ss) if (i.name.equals("Bob")) bobId = i.id;
        s.kick(bobId);
        check("kicked device's token dies (401)", req("GET", "/api/ping", b1, null).code == 401);
        check("kicked device may rejoin", login("devB", "pw123", "Bob") != null);
        for (MiniServer.SessionInfo i : s.sessions()) if (i.name.equals("Bob")) bobId = i.id;
        s.block(bobId);
        check("blocklist has 1 entry", cfg.blocks().size() == 1);
        check("blocked device cannot log in again (by device id)", login("devB", "pw123", "Bob") == null);
        cfg.removeBlock(cfg.blocks().get(0).id);
        check("unblock lets the device in again", login("devB", "pw123", "Bob") != null);

        System.out.println("== live settings");
        cfg.password = "newpass";
        s.kickAll();
        check("kickAll invalidates sessions", req("GET", "/api/ping", a1, null).code == 401);
        check("old password refused after change", login("devA", "pw123", "A") == null);
        check("new password accepted", login("devA", "newpass", "A") != null);
        cfg.password = "";
        check("empty password = open room", login("devZ", "", "Zed") != null);
        s.stop();
    }

    static boolean mountNames() {
        Set<String> used = new HashSet<String>();
        String a = CompositeFileSource.mountName("Photos/2026", used), b = CompositeFileSource.mountName("Photos/2026", used);
        String c = CompositeFileSource.mountName(".hidden", used), d = CompositeFileSource.mountName("", used);
        return a.equals("Photos_2026") && b.equals("Photos_2026 (2)") && c.equals("hidden") && d.equals("Folder");
    }

    static String sid(MiniServer s, String name) {
        for (MiniServer.SessionInfo i : s.sessions()) if (i.name.equals(name)) return i.id;
        return "";
    }

    static void permissions(File main, File extra) throws Exception {
        ServerConfig c = new ServerConfig();
        c.maxClients = 10;
        MiniServer s = start(c, composite(main, extra, false, null));
        String host = token(req("POST", "/api/login", null, "hostToken=" + s.hostToken()));
        String t = login("p1", "", "Pat");
        String note = "path=Main%2Fdocs%2Fnote.txt";

        System.out.println("== per-device rename / edit / delete");
        check("rename refused by default -> 403", req("POST", "/api/rename", t, note + "&name=memo.txt").code == 403);
        check("save refused by default -> 403", post("/api/save?path=Main%2Fdocs%2Fnote.txt", t, "x".getBytes()).code == 403);
        s.setPerms(sid(s, "Pat"), ServerConfig.P_RENAME);
        check("info shows canRename only", req("GET", "/api/info", t, null).text().contains("\"canRename\":true,\"canEdit\":false"));
        check("ping reports the new permission", req("GET", "/api/ping", t, null).text().contains("\"ren\":true"));
        check("cross-site rename refused", raw("POST", "/api/rename", t, "http://evil.example", note + "&name=memo.txt") == 403 && new File(main, "docs/note.txt").exists());
        check("rename with a slash -> 400", req("POST", "/api/rename", t, note + "&name=a%2Fb.txt").code == 400);
        check("rename to hidden name -> 400", req("POST", "/api/rename", t, note + "&name=.x").code == 400);
        check("rename a folder -> 400", req("POST", "/api/rename", t, "path=Main%2Fdocs&name=d2").code == 400);
        check("rename a shared-folder root -> 400", req("POST", "/api/rename", t, "path=Main&name=M2").code == 400);
        check("rename onto an existing file -> 409", req("POST", "/api/rename", t, note + "&name=r%C3%A9sum%C3%A9.txt").code == 409);
        check("rename a missing file -> 404", req("POST", "/api/rename", t, "path=Main%2Fnope.txt&name=z.txt").code == 404);
        check("rename traversal -> 400", req("POST", "/api/rename", t, "path=..%2F..%2Fx&name=z.txt").code == 400);
        Res rn = req("POST", "/api/rename", t, note + "&name=memo.txt");
        check("rename works", rn.code == 200 && new File(main, "docs/memo.txt").exists() && !new File(main, "docs/note.txt").exists());
        check("listing shows the new name", req("GET", "/api/list?path=Main%2Fdocs", t, null).text().contains("memo.txt"));
        check("save still refused (only rename granted)", post("/api/save?path=Main%2Fdocs%2Fmemo.txt", t, "x".getBytes()).code == 403);
        check("delete still refused", req("POST", "/api/delete", t, "path=Main%2Fdocs%2Fmemo.txt").code == 403);

        String t2 = login("p1", "", "Pat");
        check("permission follows the device across sign-ins", req("POST", "/api/rename", t2, "path=Main%2Fdocs%2Fmemo.txt&name=note.txt").code == 200);

        s.setPerms(sid(s, "Pat"), ServerConfig.P_RENAME | ServerConfig.P_EDIT | ServerConfig.P_DELETE);
        Res sv = post("/api/save?path=Main%2Fdocs%2Fnote.txt", t2, "brand new text \u00e9".getBytes("UTF-8"));
        check("save works", sv.code == 200 && new String(Files.readAllBytes(new File(main, "docs/note.txt").toPath()), "UTF-8").equals("brand new text \u00e9"));
        check("saved text is served back", req("GET", "/api/file?path=Main%2Fdocs%2Fnote.txt", t2, null).text().equals("brand new text \u00e9".getBytes("UTF-8").length == 0 ? "" : new String("brand new text \u00e9".getBytes("UTF-8"), "ISO-8859-1")) || req("GET", "/api/file?path=Main%2Fdocs%2Fnote.txt", t2, null).code == 200);
        check("save a non-text file -> 400", post("/api/save?path=Main%2Fphoto.jpg", t2, "x".getBytes()).code == 400);
        check("save a folder -> 400", post("/api/save?path=Main%2Fdocs", t2, "x".getBytes()).code == 400);
        check("save a missing file -> 404", post("/api/save?path=Main%2Fnope.txt", t2, "x".getBytes()).code == 404);
        check("cross-site save refused", raw("POST", "/api/save?path=Main%2Fdocs%2Fnote.txt", t2, "http://evil.example", "x") == 403);
        int big;
        try { big = post("/api/save?path=Main%2Fdocs%2Fnote.txt", t2, new byte[600000]).code; } catch (IOException e) { big = 413; }
        check("oversized save -> 413", big == 413);
        write(new File(main, "docs/tmp.txt"), "tmp".getBytes());
        check("delete granted per device works", req("POST", "/api/delete", t2, "path=Main%2Fdocs%2Ftmp.txt").code == 200 && !new File(main, "docs/tmp.txt").exists());
        s.setPerms(sid(s, "Pat"), 0);
        check("removing the permissions blocks rename again", req("POST", "/api/rename", t2, "path=Main%2Fdocs%2Fnote.txt&name=zz.txt").code == 403);

        System.out.println("== per-device game only");
        s.setPerms(sid(s, "Pat"), ServerConfig.P_GAME_ONLY | ServerConfig.P_DELETE);
        check("game-only device cannot list -> 403", req("GET", "/api/list?path=", t2, null).code == 403);
        check("game-only device cannot read files -> 403", req("GET", "/api/file?path=Main%2Fdocs%2Fnote.txt", t2, null).code == 403);
        check("game-only device cannot get thumbs/zip", req("GET", "/api/thumb?path=Main%2Fphoto.jpg", t2, null).code == 403 && req("GET", "/api/zip?path=Main", t2, null).code == 403);
        check("game-only device cannot delete even if granted", req("POST", "/api/delete", t2, "path=Main%2Fphoto.jpg").code == 403 && new File(main, "photo.jpg").exists());
        check("info says gameOnly with everything off", req("GET", "/api/info", t2, null).text().contains("\"canDelete\":false,\"canRename\":false,\"canEdit\":false,\"gameOnly\":true"));
        check("ping stays allowed and says go", req("GET", "/api/ping", t2, null).text().contains("\"go\":true"));
        String other = login("p2", "", "Quinn");
        check("other devices are unaffected", req("GET", "/api/list?path=", other, null).code == 200);
        s.setPerms(sid(s, "Pat"), 0);
        check("clearing game-only restores browsing", req("GET", "/api/list?path=", t2, null).code == 200);

        System.out.println("== game-only hosting mode");
        c.gameOnly = true;
        check("everyone is game-only: list -> 403", req("GET", "/api/list?path=", other, null).code == 403);
        check("info reports game enabled + gameOnly", req("GET", "/api/info", other, null).text().contains("\"gameEnabled\":true") && req("GET", "/api/info", other, null).text().contains("\"gameOnly\":true"));
        check("host still sees the files", req("GET", "/api/list?path=", host, null).code == 200 && req("GET", "/api/info", host, null).text().contains("\"gameOnly\":false"));
        String late = login("p3", "", "Rhea");
        check("new guests can join but only get the game", req("GET", "/api/info", late, null).text().contains("\"gameOnly\":true") && req("GET", "/api/file?path=Main%2Fmovie.mp4", late, null).code == 403);
        c.gameOnly = false;
        check("turning it off restores browsing", req("GET", "/api/list?path=", other, null).code == 200);
        s.stop();
    }

    static void community(File main, File extra) throws Exception {
        ServerConfig c = new ServerConfig();
        c.maxClients = 10;
        ThumbSource[] hold = new ThumbSource[1];
        MiniServer s = start(c, composite(main, extra, true, hold));
        String t = login("g1", "", "Gina");

        System.out.println("== capabilities");
        check("guest cannot upload or delete by default", req("GET", "/api/info", t, null).text().contains("\"canUpload\":false,\"canDelete\":false"));
        String host = token(req("POST", "/api/login", null, "hostToken=" + s.hostToken()));
        check("host can always upload and delete", req("GET", "/api/info", host, null).text().contains("\"canUpload\":true,\"canDelete\":true"));

        System.out.println("== thumbnails");
        Res th = req("GET", "/api/thumb?path=Main%2Fphoto.jpg&w=200", t, null);
        check("thumbnail served as jpeg", th.code == 200 && th.head("Content-Type").equals("image/jpeg") && th.body.length == 6 && (th.body[5] & 0xff) == 200);
        check("thumbnail is browser-cacheable", th.head("Cache-Control").contains("max-age=86400") && th.head("ETag").length() > 2);
        req("GET", "/api/thumb?path=Main%2Fphoto.jpg&w=200", t, null);
        check("second request served from cache (1 decode)", hold[0].calls == 1);
        check("conditional request -> 304", req("GET", "/api/thumb?path=Main%2Fphoto.jpg&w=200", t, null, "If-None-Match", th.head("ETag")).code == 304);
        check("video also gets a thumbnail", req("GET", "/api/thumb?path=Main%2Fmovie.mp4", t, null).code == 200);
        check("size is clamped", req("GET", "/api/thumb?path=Main%2Fphoto.jpg&w=99999", t, null).body[5] == (byte) 640);
        check("text file has no thumbnail -> 404", req("GET", "/api/thumb?path=Main%2Fdocs%2Fnote.txt", t, null).code == 404);
        check("thumb traversal rejected", req("GET", "/api/thumb?path=..%2f..%2fphoto.jpg", t, null).code == 400);

        System.out.println("== folder ZIP");
        check("zip needs login", req("GET", "/api/zip?path=Extra%20Files%2Ftree", null, null).code == 401);
        Res z = req("GET", "/api/zip?path=Extra%20Files%2Ftree", t, null);
        check("zip is an attachment named tree.zip", z.code == 200 && z.head("Content-Disposition").contains("tree.zip"));
        Map<String, byte[]> got = unzip(z.body);
        check("zip has exactly the 3 visible files " + got.keySet(), got.keySet().equals(new TreeSet<String>(Arrays.asList("a.txt", "sub/b.bin", "sub/ünï.txt"))));
        check("zip contents identical", "alpha".equals(new String(got.get("a.txt"), "UTF-8")) && Arrays.equals(got.get("sub/b.bin"), rnd(100000)));
        Map<String, byte[]> all = unzip(req("GET", "/api/zip?path=", t, null).body);
        check("zip of the whole share keeps folder names", all.containsKey("Main/docs/note.txt") && all.containsKey("Extra Files/readme.txt"));
        check("zip traversal rejected", req("GET", "/api/zip?path=..%2f..", t, null).code == 400);
        check("zip of a file -> 404", req("GET", "/api/zip?path=Extra%20Files%2Ftree%2Fa.txt", t, null).code == 404);

        System.out.println("== uploads");
        File tree = new File(extra, "tree");
        byte[] small = rnd(200000);
        check("upload refused while off -> 403", post("/api/upload?path=Extra%20Files%2Ftree&name=up.bin", t, rnd(50)).code == 403);
        c.allowUploads = true;
        check("info shows upload allowed", req("GET", "/api/info", t, null).text().contains("\"canUpload\":true"));
        check("upload needs login -> 401", post("/api/upload?path=Extra%20Files%2Ftree&name=up.bin", null, rnd(50)).code == 401);
        Res u = post("/api/upload?path=Extra%20Files%2Ftree&name=up.bin", t, small);
        check("upload ok", u.code == 200 && u.text().contains("\"name\":\"up.bin\""));
        check("uploaded bytes identical", Arrays.equals(Files.readAllBytes(new File(tree, "up.bin").toPath()), small));
        check("same name gets ' (1)' and old file kept", post("/api/upload?path=Extra%20Files%2Ftree&name=up.bin", t, rnd(10)).text().contains("up (1).bin") && new File(tree, "up.bin").length() == 200000);
        check("upload to the folder chooser (root) refused", post("/api/upload?path=&name=x.txt", t, rnd(5)).code == 400);
        check("name with ../ rejected", post("/api/upload?path=Extra%20Files%2Ftree&name=..%2fevil.txt", t, rnd(5)).code == 400);
        check("dotfile name rejected", post("/api/upload?path=Extra%20Files%2Ftree&name=.bad", t, rnd(5)).code == 400);
        check("folder traversal rejected", post("/api/upload?path=..%2f..&name=x.txt", t, rnd(5)).code == 400);
        check("missing folder -> 404", post("/api/upload?path=Nope&name=x.txt", t, rnd(5)).code == 404);
        check("cross-site upload refused -> 403", raw("POST", "/api/upload?path=Extra%20Files%2Ftree&name=evil.txt", t, "http://evil.example", "x") == 403 && !new File(tree, "evil.txt").exists());
        c.maxUploadBytes = 1000;
        check("over the size limit -> 413, nothing written", post("/api/upload?path=Extra%20Files%2Ftree&name=big.bin", t, rnd(5000)).code == 413 && !new File(tree, "big.bin").exists());
        c.maxUploadBytes = 100L * 1024 * 1024;
        byte[] big = rnd(8 * 1024 * 1024);
        check("8 MB streamed upload intact", post("/api/upload?path=Extra%20Files%2Ftree&name=big8.bin", t, big).code == 200 && Arrays.equals(Files.readAllBytes(new File(tree, "big8.bin").toPath()), big));
        try (Socket k = new Socket("127.0.0.1", port)) {
            OutputStream o = k.getOutputStream();
            o.write(("POST /api/upload?path=Extra%20Files%2Ftree&name=partial.bin HTTP/1.1\r\nHost: x\r\nCookie: ls_token=" + t + "\r\nContent-Length: 100000\r\n\r\n").getBytes());
            o.write(rnd(10000));
            o.flush();
        }
        Thread.sleep(600);
        check("half-finished upload leaves no file behind", !new File(tree, "partial.bin").exists());

        System.out.println("== delete");
        check("delete refused while off -> 403", req("POST", "/api/delete", t, "path=Extra%20Files%2Ftree%2Fup.bin").code == 403 && new File(tree, "up.bin").exists());
        c.allowDelete = true;
        check("info shows delete allowed", req("GET", "/api/info", t, null).text().contains("\"canDelete\":true"));
        check("cross-site delete refused -> 403", raw("POST", "/api/delete", t, "http://evil.example", "path=Extra%20Files%2Ftree%2Fup.bin") == 403 && new File(tree, "up.bin").exists());
        check("delete traversal rejected", req("POST", "/api/delete", t, "path=..%2f..%2fx").code == 400);
        check("deleting a shared folder is refused", req("POST", "/api/delete", t, "path=Extra%20Files%2Ftree").code == 400 && tree.isDirectory());
        check("deleting a folder root is refused", req("POST", "/api/delete", t, "path=Main").code == 400);
        check("deleting a missing file -> 404", req("POST", "/api/delete", t, "path=Main%2Fnope.txt").code == 404);
        check("delete works", req("POST", "/api/delete", t, "path=Extra%20Files%2Ftree%2Fup.bin").code == 200 && !new File(tree, "up.bin").exists());
        check("other files untouched", new File(tree, "up (1).bin").exists() && new File(tree, "a.txt").exists());
        check("deleted file is gone from the listing", !req("GET", "/api/list?path=Extra%20Files%2Ftree", t, null).text().contains("\"up.bin\""));

        System.out.println("== activity log");
        req("GET", "/api/file?path=Main%2Fdocs%2Fnote.txt", t, null);
        c.password = "pw";
        req("POST", "/api/login", null, "device=bad&name=Mallory&password=wrong");
        c.password = "";
        Set<String> kinds = new HashSet<String>();
        for (ActivityLog.Entry e : c.log.recent()) kinds.add(e.kind);
        check("log recorded join/view/zip/upload/delete/denied " + kinds, kinds.containsAll(Arrays.asList("join", "view", "zip", "upload", "delete", "denied")));
        check("newest entry first", c.log.recent().get(0).time >= c.log.recent().get(c.log.recent().size() - 1).time);
        c.log.clear();
        check("log can be cleared", c.log.recent().isEmpty());

        System.out.println("== session length");
        c.sessionMs = 1500;
        String guest = login("g2", "", "Short");
        check("fresh session works", req("GET", "/api/ping", guest, null).code == 200);
        Thread.sleep(2000);
        check("guest signs in again after the session length", req("GET", "/api/ping", guest, null).code == 401);
        check("host never expires", req("GET", "/api/ping", host, null).code == 200);
        c.sessionMs = 0;
        String forever = login("g3", "", "Long");
        Thread.sleep(1800);
        check("session length 0 = never expires", req("GET", "/api/ping", forever, null).code == 200);
        s.stop();
    }

    static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> got = new TreeMap<String, byte[]>();
        try (ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zi.getNextEntry()) != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = zi.read(b)) > 0) bo.write(b, 0, n);
                got.put(e.getName(), bo.toByteArray());
            }
        }
        return got;
    }
}
