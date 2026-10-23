package com.localshare.app.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Game rules, tested without sockets: tick() is called directly and clients are recorders. */
public class ArenaTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
    }

    static final class Rec implements GameHub.Out {
        final List<String> msgs = new ArrayList<String>();
        boolean closed;

        public void send(String t) { msgs.add(t); }
        public void close() { closed = true; }
        public boolean isDead() { return closed; }

        String snap() {
            for (int i = msgs.size() - 1; i >= 0; i--) if (msgs.get(i).startsWith("{\"t\":\"s\"")) return msgs.get(i);
            return "";
        }

        boolean got(String frag) {
            for (String m : msgs) if (m.contains(frag)) return true;
            return false;
        }
    }

    static final Executor DIRECT = new Executor() {
        public void execute(Runnable r) { r.run(); }
    };

    static GameHub hub(ServerConfig c, int bots) {
        GameHub h = new GameHub(c, DIRECT, false);
        h.botFill = bots;
        return h;
    }

    static void ticks(GameHub h, int n) {
        for (int i = 0; i < n; i++) h.tick();
    }

    public static int passCount() {
        return pass;
    }

    public static int run() {
        pass = 0;
        fail = 0;
        ServerConfig cfg = new ServerConfig();

        System.out.println("== arena: bots");
        GameHub h = hub(cfg, 1);
        Rec ra = new Rec(), rb = new Rec();
        GameHub.Player a = h.join("ta", "aaaa", "Alice", ra);
        h.tick();
        check("a lone player gets exactly 1 bot", h.botCount() == 1 && ra.snap().contains("\"n\":2"));
        h.join("tb", "bbbb", "Bob", rb);
        h.tick();
        check("second device joins; still only 1 bot", h.botCount() == 1 && ra.snap().contains("\"n\":3"));
        ticks(h, 400);
        check("bots eat orbs and grow over 20 s", h.maxBotRadius() > 19);
        check("bot count stable", h.botCount() == 1);
        h.leave(a);
        h.drop("tb");
        h.tick();
        check("no humans -> bots removed", h.botCount() == 0);

        System.out.println("== arena: rules (no bots)");
        h = hub(cfg, 0);
        ra = new Rec();
        rb = new Rec();
        a = h.join("ta", "aaaa", "Alice", ra);
        GameHub.Player b = h.join("tb", "bbbb", "Bob", rb);
        double[] o = h.orb(0);
        h.place(a, o[0], o[1], 18);
        h.place(b, 100, 100, 18);
        h.tick();
        check("eating an orb grows the player", h.stateOf(a)[2] > 18.05);

        h.place(a, 1500, 1500, 60);
        h.place(b, 1510, 1500, 18);
        h.tick();
        check("big player eats small one", h.stateOf(b)[3] == 1 && h.stateOf(a)[2] > 61.5);
        check("eaten player is told who ate them + score", rb.got("\"t\":\"dead\"") && rb.got("\"by\":\"Alice\""));
        check("eater gets no death message", !ra.got("\"t\":\"dead\""));
        h.tick();
        check("dead player is out of the snapshot", !ra.snap().contains("bbbb"));
        h.input(b, "r");
        check("respawn brings them back at start size", h.stateOf(b)[3] == 0 && Math.abs(h.stateOf(b)[2] - 18) < 1e-9);

        h.place(a, 1500, 1500, 40);
        h.place(b, 1505, 1500, 38);
        h.tick();
        check("similar sizes do not eat each other", h.stateOf(a)[3] == 0 && h.stateOf(b)[3] == 0);

        h.place(a, 1500, 1500, 60);
        h.place(b, 1570, 1500, 18);
        h.tick();
        check("must overlap enough to eat (distance 70 is safe)", h.stateOf(b)[3] == 0);

        System.out.println("== arena: movement");
        h.parkOrbs();
        h.place(a, 1000, 1000, 40);
        h.place(b, 2000, 2000, 40);
        h.input(a, "m 1 0");
        h.input(b, "m 1 0");
        h.input(b, "b 1");
        ticks(h, 10);
        double[] sa = h.stateOf(a), sb = h.stateOf(b);
        check("boost moves faster (" + (int) (sa[0] - 1000) + " vs " + (int) (sb[0] - 2000) + ")", (sb[0] - 2000) > (sa[0] - 1000) * 1.4);
        check("boost costs size", sb[2] < sa[2] - 0.2);
        h.place(b, 2000, 2000, 22.5);
        ticks(h, 80);
        check("boost never shrinks below the minimum", h.stateOf(b)[2] >= 21.9);
        h.input(a, "b 0");
        h.place(a, 5, 5, 18);
        h.input(a, "m -1 -1");
        ticks(h, 6);
        check("players stay inside the arena", h.stateOf(a)[0] >= 17.9 && h.stateOf(a)[1] >= 17.9);
        h.input(a, "m NaN NaN");
        h.input(a, "m x");
        h.input(a, "b");
        h.input(a, "zzz");
        h.place(a, 800, 800, 18);
        h.tick();
        check("malformed input is ignored", Math.abs(h.stateOf(a)[0] - 800) < 1);

        System.out.println("== arena: what each client sees");
        h.place(a, 200, 200, 18);
        h.place(b, 2800, 2800, 18);
        h.tick();
        String snap = ra.snap();
        String view = snap.substring(snap.indexOf("\"p\":["), snap.indexOf("],\"o\":"));
        check("far-away player is culled from the view", view.contains("aaaa") && !view.contains("bbbb"));
        String mini = snap.substring(snap.indexOf("\"mm\":["));
        check("but still on the minimap", mini.contains("\"bbbb\",28"));
        check("leaderboard present", ra.snap().contains("\"lb\":[["));

        System.out.println("== arena: switches off");
        cfg.gameEnabled = false;
        h.tick();
        check("turning the game off disconnects everyone", ra.closed && rb.closed);
        cfg.gameEnabled = true;
        return fail;
    }

    public static void main(String[] args) {
        int f = run();
        System.out.println("\nARENA RESULT: " + pass + " passed, " + fail + " failed");
        System.exit(f == 0 ? 0 : 1);
    }
}
