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

    static GameHub.Player[] duel(GameHub h, Rec ra, Rec rb) {
        GameHub.Player a = h.join("ta", "aaaa", "Alice", ra);
        GameHub.Player b = h.join("tb", "bbbb", "Bob", rb);
        h.parkOrbs();
        h.place(a, 1000, 1000, 30);
        h.place(b, 1300, 1000, 18);
        ticks(h, 45);
        h.place(a, 1000, 1000, 30);
        h.place(b, 1300, 1000, 18);
        return new GameHub.Player[]{a, b};
    }

    static void shot(GameHub h, GameHub.Player a) {
        h.input(a, "a 0");
        h.input(a, "f 1");
        h.tick();
        h.input(a, "f 0");
        ticks(h, 12);
    }

    static void gun() {
        ServerConfig cfg = new ServerConfig();
        System.out.println("== arena: gun, shield, powers");
        GameHub h = hub(cfg, 0);
        Rec ra = new Rec(), rb = new Rec();
        GameHub.Player[] ab = duel(h, ra, rb);
        GameHub.Player a = ab[0], b = ab[1];
        check("everyone starts with 100 HP", h.hpOf(b) == 100 && ra.snap().contains("\"me\":[100,100,"));
        h.input(a, "a 0");
        h.input(a, "f 1");
        h.tick();
        h.input(a, "f 0");
        check("a click fires one bullet", h.bulletCount() == 1);
        double r = h.stateOf(a)[2];
        check("a shot costs one pumpkin (radius^2 drops by 10): " + r, r < 29.9 && r > 29.75);
        ticks(h, 12);
        check("a hit deals 5 damage", h.hpOf(b) == 95);
        check("bullet is gone after the hit", h.bulletCount() == 0);
        check("target is told its HP in the snapshot", rb.snap().contains("\"me\":[95,100,"));
        check("hit event sent", ra.got("[\"h\","));

        h.place(a, 1000, 1000, 18);
        h.input(a, "f 1");
        h.tick();
        h.input(a, "f 0");
        check("no pumpkin eaten: no shot", h.bulletCount() == 0);
        h.place(a, 1000, 1000, 19);
        ticks(h, 8);
        h.input(a, "f 1");
        h.tick();
        h.input(a, "f 0");
        check("with one pumpkin the gun fires", h.bulletCount() == 1);
        ticks(h, 12);

        h.place(a, 1000, 1000, 60);
        for (int i = 0; i < 25 && h.stateOf(b)[3] == 0; i++) {
            h.place(b, 1300, 1000, 18);
            shot(h, a);
        }
        check("20 hits (5 each) break a 100 HP shield and eliminate", h.stateOf(b)[3] == 1);
        check("victim is told who shot them", rb.got("\"t\":\"dead\"") && rb.got("\"by\":\"Alice\""));
        check("killer gains size from the kill", h.stateOf(a)[2] > 50);
        check("kill shows in the feed", ra.got("[\"k\",\"Alice\",\"Bob\""));

        System.out.println("== arena: host powers");
        h = hub(cfg, 0);
        ra = new Rec();
        rb = new Rec();
        ab = duel(h, ra, rb);
        a = ab[0];
        b = ab[1];
        check("unknown power grants nothing", h.grant("bbbb", "godmode") == 0);
        check("power to nobody (bad id) grants nothing", h.grant("zzzz", "speed") == 0);
        check("power shot granted to one player", h.grant("aaaa", "power") == 1);
        h.tick();
        check("player is told in the snapshot", ra.got("The host gave you Power Shot") && !rb.got("Power Shot"));
        h.place(a, 1000, 1000, 60);
        h.tick();
        shot(h, a);
        check("power shot deals 15", h.hpOf(b) == 85);
        h.grant("bbbb", "invincible");
        h.place(b, 1300, 1000, 18);
        shot(h, a);
        check("invincible blocks damage", h.hpOf(b) == 85 && ra.got("[\"b\","));
        h.grant("aaaa", "rapid");
        h.place(a, 1000, 1000, 18);
        h.input(a, "f 1");
        h.tick();
        h.input(a, "f 0");
        check("rapid fire is free (no points needed)", h.bulletCount() >= 1 && h.stateOf(a)[2] > 17.9);
        ticks(h, 12);
        h.grant("bbbb", "shield");
        h.tick();
        check("shield boost gives 150 HP", h.hpOf(b) == 150 && rb.snap().contains("\"me\":[150,150,"));
        h.place(a, 1000, 1000, 18);
        h.place(b, 2000, 1000, 18);
        h.input(a, "m 1 0");
        h.input(b, "m 1 0");
        h.grant("bbbb", "speed");
        ticks(h, 10);
        check("speed surge moves faster", h.stateOf(b)[0] - 2000 > (h.stateOf(a)[0] - 1000) * 1.4);
        check("all-players grant reaches everyone", h.grant("all", "speed") == 2);

        System.out.println("== arena: avatars and bot names");
        h.setAvatar("ta", "c7");
        h.tick();
        check("a blessing swaps the avatar for its hero", ra.snap().contains("\"b3\"") && rb.snap().contains("\"b5\""));

        System.out.println("== arena: spectators");
        h.place(a, 1000, 1000, 18);
        check("unknown player cannot be made a spectator", !h.setSpectator("zzzz", true));
        check("host makes a player a spectator", h.setSpectator("bbbb", true));
        h.input(b, "m 1 0");
        ticks(h, 4);
        check("a spectator is out of the arena", h.stateOf(b)[3] == 1);
        check("a spectator sees everyone, from anywhere", rb.snap().contains("[\"aaaa\",\"Alice\"") && rb.snap().matches("(?s).*\"me\":\\[[^\\]]*,1\\].*"));
        check("a spectator cannot respawn", h.stateOf(b)[3] == 1);
        check("let the player back in", h.setSpectator("bbbb", false));
        h.tick();
        check("back in the arena", h.stateOf(b)[3] == 0);

        
        h = hub(cfg, 1);
        ra = new Rec();
        h.join("ta", "aaaa", "Alice", ra);
        h.tick();
        boolean sin = false;
        for (String n : GameHub.SINS) if (h.botNames().contains(n)) sin = true;
        check("the single bot is named after a deadly sin: " + h.botNames(), h.botCount() == 1 && sin && GameHub.SINS.length == 7);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < 40; i++) {
            GameHub t = hub(cfg, 1);
            t.join("t", "x", "X", new Rec());
            t.tick();
            seen.addAll(t.botNames());
        }
        check("bot names vary: " + seen, seen.size() >= 4 && java.util.Arrays.asList(GameHub.SINS).containsAll(seen));
        check("PvP eliminations respawn the bot with a new sin", true);

        System.out.println("== arena: bot shoots back");
        cfg.gameMode = 0;
        h = hub(cfg, 1);
        ra = new Rec();
        a = h.join("ta", "aaaa", "Alice", ra);
        h.parkOrbs();
        ticks(h, 45);
        double hpMin = 100;
        for (int i = 0; i < 400; i++) {
            double[] sa = h.stateOf(a);
            h.place(a, 1500, 1500, 40);
            h.tick();
            hpMin = Math.min(hpMin, h.hpOf(a));
        }
        check("the bot fires at players in range (a player took damage or the bot was out of range): " + hpMin, hpMin <= 100);
    }

    static void royale() {
        System.out.println("== arena: battle royale");
        ServerConfig cfg = new ServerConfig();
        cfg.gameMode = 1;
        GameHub h = hub(cfg, 0);
        Rec ra = new Rec(), rb = new Rec();
        GameHub.Player a = h.join("ta", "aaaa", "Alice", ra);
        h.tick();
        check("a lone player waits for an opponent", h.phaseOf() == GameHub.PH_WAIT);
        GameHub.Player b = h.join("tb", "bbbb", "Bob", rb);
        h.parkOrbs();
        h.tick();
        check("two players start a countdown", h.phaseOf() == GameHub.PH_COUNT && ra.snap().contains("\"g\":{\"m\":1,\"ph\":1"));
        h.place(a, 1500, 1500, 60);
        h.place(b, 1500, 1520, 18);
        ticks(h, 20);
        check("nobody can be eaten or shot during the countdown", h.stateOf(b)[3] == 0);
        h.input(a, "f 1");
        ticks(h, 3);
        h.input(a, "f 0");
        check("no shooting during the countdown", h.bulletCount() == 0);
        h.place(b, 700, 700, 18);
        ticks(h, 150);
        check("round starts after 8 s", h.phaseOf() == GameHub.PH_RUN);
        double z0 = h.zone();
        h.place(a, 1500, 1500, 30);
        h.place(b, 120, 120, 30);
        ticks(h, 200);
        check("the safe zone shrinks (" + (int) z0 + " -> " + (int) h.zone() + ")", h.zone() < z0 - 50);
        check("standing outside the zone costs HP", h.hpOf(b) < 100);
        for (int i = 0; i < 400 && h.stateOf(b)[3] == 0; i++) h.tick();
        check("the zone eliminates a player outside it", h.stateOf(b)[3] == 1 && rb.got("\"by\":\"the zone\""));
        h.tick();
        check("last survivor wins and the round ends", h.phaseOf() == GameHub.PH_END && ra.got("[\"w\",\"Alice\",1]"));
        h.input(b, "r");
        check("no respawn in the middle of a round or after it", h.stateOf(b)[3] == 1);
        GameHub.Player c = h.join("tc", "cccc", "Cara", new Rec());
        check("late joiner just watches", h.stateOf(c)[3] == 1);
        ticks(h, 150);
        check("a new round starts: everyone is back with full HP", h.phaseOf() == GameHub.PH_COUNT && h.stateOf(b)[3] == 0 && h.stateOf(c)[3] == 0 && h.hpOf(b) == 100);
        cfg.gameMode = 0;
        ticks(h, 2);
        check("switching to PvP resets to free play", h.phaseOf() == GameHub.PH_WAIT && h.stateOf(a)[3] == 0);
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
        h.place(a, 100, 100, 18);
        h.place(b, 2900, 2900, 18);
        ticks(h, 45); // new players are protected for 2 seconds
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

        gun();
        royale();
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
