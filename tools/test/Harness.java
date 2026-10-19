import com.localshare.app.server.*;

import java.io.*;
import java.net.*;
import java.util.*;

/** Desktop smoke test for the server core. Usage: Harness test | Harness serve */
public class Harness {
    static int port;
    static int fail = 0, pass = 0;

    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
    }

    static class Res { int code; byte[] body; Map<String, List<String>> h; String text() { return new String(body); } }

    static Res req(String method, String path, String cookie, String form, String... hdr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        if (cookie != null) c.setRequestProperty("Cookie", "ls_token=" + cookie);
        for (int i = 0; i + 1 < hdr.length; i += 2) c.setRequestProperty(hdr[i], hdr[i + 1]);
        if (form != null) { c.setDoOutput(true); c.getOutputStream().write(form.getBytes("UTF-8")); }
        Res r = new Res();
        r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); }
        r.body = bo.toByteArray();
        r.h = c.getHeaderFields();
        return r;
    }

    static String token(Res r) {
        for (String s : r.h.get("Set-Cookie")) if (s.startsWith("ls_token=")) return s.substring(9, s.indexOf(';'));
        return null;
    }

    static String login(String dev, String pw, String name) throws Exception {
        Res r = req("POST", "/api/login", null, "device=" + dev + "&name=" + name + "&password=" + pw);
        return r.code == 200 ? token(r) : null;
    }

    public static void main(String[] a) throws Exception {
        File root = new File(a[1]);
        final File assets = new File(a[2]);
        ServerConfig cfg = new ServerConfig();
        cfg.password = "pw123";
        cfg.maxClients = 2;
        MiniServer s = new MiniServer(cfg, new DirFileSource(root), new AssetProvider() {
            public byte[] get(String n) throws IOException {
                File f = new File(assets, n);
                if (!f.isFile()) return null;
                byte[] b = new byte[(int) f.length()];
                try (DataInputStream d = new DataInputStream(new FileInputStream(f))) { d.readFully(b); }
                return b;
            }
        });
        s.start(a[0].equals("serve") ? 18080 : 0);
        port = s.port();
        if (a[0].equals("serve")) {
            System.out.println("READY " + port + " hostToken=" + s.hostToken());
            Thread.sleep(Long.MAX_VALUE);
        }

        System.out.println("== static + info");
        check("GET / serves index.html", req("GET", "/", null, null).text().contains("LocalShare"));
        check("pdf.js asset served", req("GET", "/static/pdf.min.js", null, null).body.length > 100000);
        check("asset traversal blocked", req("GET", "/static/..%2f..%2fetc%2fpasswd", null, null).code == 404);
        Res info = req("GET", "/api/info", null, null);
        check("info reports needPassword", info.text().contains("\"needPassword\":true"));

        System.out.println("== auth");
        check("list without login -> 401", req("GET", "/api/list?path=", null, null).code == 401);
        check("file without login -> 401", req("GET", "/api/file?path=movie.mp4", null, null).code == 401);
        check("ws without login rejected", req("GET", "/ws", null, null, "Upgrade", "websocket", "Sec-WebSocket-Key", "x").code != 101);
        String t1 = login("dev1", "pw123", "Alice");
        check("correct password logs in", t1 != null);

        System.out.println("== files");
        Res l = req("GET", "/api/list?path=", t1, null);
        check("list root", l.code == 200 && l.text().contains("movie.mp4") && l.text().contains("docs") && l.text().contains("\"k\":\"video\""));
        check("dotfiles hidden", !l.text().contains(".secret"));
        check("list subfolder", req("GET", "/api/list?path=docs", t1, null).text().contains("note.txt"));
        check("unicode filename", req("GET", "/api/list?path=docs", t1, null).text().contains("résumé.txt"));
        check("traversal ../ rejected", req("GET", "/api/list?path=..%2f..", t1, null).code == 400);
        check("traversal in file rejected", req("GET", "/api/file?path=docs%2f..%2f..%2fetc%2fpasswd", t1, null).code == 400);
        check("absolute path stays inside root", req("GET", "/api/file?path=%2Fetc%2Fpasswd", t1, null).code == 404);
        check("missing file 404", req("GET", "/api/file?path=nope.bin", t1, null).code == 404);

        byte[] orig = java.nio.file.Files.readAllBytes(new File(root, "movie.mp4").toPath());
        Res full = req("GET", "/api/file?path=movie.mp4", t1, null);
        check("full download identical (" + orig.length + " B)", full.code == 200 && Arrays.equals(full.body, orig));
        check("Accept-Ranges advertised", "bytes".equals(full.h.get("Accept-Ranges") == null ? null : full.h.get("Accept-Ranges").get(0)));
        Res p = req("GET", "/api/file?path=movie.mp4", t1, null, "Range", "bytes=1000-1999");
        check("range 206 + exact bytes", p.code == 206 && p.body.length == 1000 && Arrays.equals(p.body, Arrays.copyOfRange(orig, 1000, 2000)));
        check("Content-Range header", p.h.get("Content-Range").get(0).equals("bytes 1000-1999/" + orig.length));
        Res open = req("GET", "/api/file?path=movie.mp4", t1, null, "Range", "bytes=" + (orig.length - 500) + "-");
        check("open-ended range", open.code == 206 && Arrays.equals(open.body, Arrays.copyOfRange(orig, orig.length - 500, orig.length)));
        Res suf = req("GET", "/api/file?path=movie.mp4", t1, null, "Range", "bytes=-100");
        check("suffix range", suf.code == 206 && Arrays.equals(suf.body, Arrays.copyOfRange(orig, orig.length - 100, orig.length)));
        check("unsatisfiable range 416", req("GET", "/api/file?path=movie.mp4", t1, null, "Range", "bytes=" + (orig.length + 10) + "-").code == 416);
        check("HEAD has no body", req("HEAD", "/api/file?path=movie.mp4", t1, null).body.length == 0);
        Res html = req("GET", "/api/file?path=page.html", t1, null);
        check("shared HTML served as text/plain", html.h.get("Content-Type").get(0).startsWith("text/plain"));
        check("dl=1 sets attachment", req("GET", "/api/file?path=docs%2fnote.txt&dl=1", t1, null).h.get("Content-Disposition").get(0).startsWith("attachment"));

        System.out.println("== password brute force");
        for (int i = 0; i < 5; i++) check("wrong password #" + (i + 1) + " -> 401", req("POST", "/api/login", null, "device=evil&password=bad" + i).code == 401);
        check("locked out after 5 -> 429", req("POST", "/api/login", null, "device=evil&password=pw123").code == 429);

        System.out.println("== device limit (max 2)");
        // loopback IP is shared by everyone here, so clear brute-force state by using a fresh server port? lockout is per IP -> wait not needed: use host token path below
        System.out.println("   (loopback locked by brute-force test; restarting server to test limits)");
        s.stop();
        cfg.password = "pw123";
        s = new MiniServer(cfg, new DirFileSource(root), new AssetProvider() { public byte[] get(String n) { return null; } });
        s.start(0);
        port = s.port();
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
        s.setFileSource(new DirFileSource(root));
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
        System.out.println("\nRESULT: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
