package com.localshare.app.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orb Arena: a server-authoritative multiplayer game at 20 ticks per second. Everyone steers a blob,
 * eats orbs to grow, boosts at the cost of size, and eats smaller blobs. Every blob also has a gun: a shot costs
 * {@link #SHOT_COST} points, deals 5 damage and every blob has 100 HP of shield. Two modes: PvP (endless, respawn)
 * and Battle Royale (rounds, a shrinking safe zone, last blob standing wins). One bot keeps the arena alive so a
 * single player is never alone. Super powers can only be granted by the host ({@link #grant}).
 */
final class GameHub {
    static final double W = 4500, H = 4500, START_R = 18, MIN_BOOST_R = 22;
    static final int ORBS = 480, BOT_FILL = 1;
    static final int SHOT_COST = 1; // one pumpkin (1 point) per shot
    static final double HP = 100, SHOT_DAMAGE = 5, POWER_DAMAGE = 15, BULLET_SPEED = 760, BULLET_LIFE = 1.15;
    static final int PH_WAIT = 0, PH_COUNT = 1, PH_RUN = 2, PH_END = 3;
    static final double ZONE_R0 = 2150, ZONE_R1 = 140, ZONE_DPS = 6;
    static final long COUNT_MS = 8000, END_MS = 7000, SHRINK_MS = 180000;
    static final int DEFAULT_AVATARS = 6;
    static final String[] POWERS = {"speed", "rapid", "power", "shield", "invincible"};
    private static final String[] POWER_LABELS = {"Speed Surge", "Rapid Fire", "Power Shot", "Iron Shield", "Invincible"};
    private static final double DT = 0.05;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** The bot is always named after one of the seven deadly sins. */
    static final String[] SINS = {"Pride", "Greed", "Lust", "Envy", "Gluttony", "Wrath", "Sloth"};

    /** Gives a bot a random sin: its name and its drawn character (picture key s1..s7) always match. */
    private void sinify(Player b) {
        int i = rnd.nextInt(SINS.length);
        b.name = SINS[i];
        b.av = "s" + (i + 1);
    }

    interface Out {
        void send(String text) throws IOException;

        void close();

        boolean isDead();
    }

    /** One WebSocket connection. */
    static final class Client implements Out {
        private final Socket sock;
        private final OutputStream out;
        volatile boolean dead;

        Client(Socket s, OutputStream o) {
            sock = s;
            out = o;
        }

        synchronized void frame(int opcode, byte[] d) throws IOException {
            out.write(0x80 | opcode);
            if (d.length < 126) {
                out.write(d.length);
            } else if (d.length < 65536) {
                out.write(126);
                out.write(d.length >> 8);
                out.write(d.length & 0xff);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) out.write((int) (((long) d.length >> (8 * i)) & 0xff));
            }
            out.write(d);
            out.flush();
        }

        @Override public void send(String text) throws IOException {
            frame(1, text.getBytes(UTF8));
        }

        @Override public void close() {
            dead = true;
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }

        @Override public boolean isDead() {
            return dead;
        }
    }

    static final class Player {
        final String id, token;
        volatile String name;
        final Out out; // null for bots
        final int hue;
        volatile String av = "d1";
        final AtomicBoolean busy = new AtomicBoolean();
        double x, y, r = START_R;
        double hp = HP, hpMax = HP;
        boolean dead;
        long respawnAt, nextShot, lastHurt, spawnUntil, speedUntil, rapidUntil, powerUntil, invUntil;
        int kills;
        volatile double dx, dy, aim;
        volatile boolean boost, fire, pending, spec;
        int think;
        final List<String> notes = new ArrayList<String>();

        Player(String id, String name, String token, Out out, int hue) {
            this.id = id;
            this.name = name;
            this.token = token;
            this.out = out;
            this.hue = hue;
        }
    }

    static final class Bullet {
        int id, hue;
        double x, y, px, py, vx, vy, life, dmg;
        Player owner;
    }

    private static final class Pending {
        final Player to;
        final String msg;

        Pending(Player to, String msg) {
            this.to = to;
            this.msg = msg;
        }
    }

    private final ServerConfig cfg;
    private final Executor io;
    private final Random rnd = new Random();
    private final Map<String, Player> players = new LinkedHashMap<String, Player>(); // by session token; guarded by this
    private final double[] ox = new double[ORBS], oy = new double[ORBS];
    private final int[] oh = new int[ORBS];
    private final ScheduledExecutorService sched;
    private int botSeq, bulletSeq, lastMode;
    int botFill = BOT_FILL;
    private long clock; // game time in ms: one tick is 50 ms
    private int phase = PH_WAIT;
    private long phaseAt;
    private double zoneR = ZONE_R0;
    private final List<Bullet> bullets = new ArrayList<Bullet>();
    private final List<String> events = new ArrayList<String>();
    private final List<Pending> outbox = new ArrayList<Pending>();

    GameHub(ServerConfig cfg, Executor io, boolean autoTick) {
        this.cfg = cfg;
        this.io = io;
        for (int i = 0; i < ORBS; i++) respawnOrb(i);
        if (!autoTick) {
            sched = null;
            return;
        }
        sched = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "game-tick");
                t.setDaemon(true);
                return t;
            }
        });
        sched.scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                try {
                    tick();
                } catch (RuntimeException ignored) {
                    // one bad tick must not stop the game loop
                }
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
    }

    void shutdown() {
        if (sched != null) sched.shutdownNow();
        synchronized (this) {
            for (Player p : players.values()) if (p.out != null) p.out.close();
            players.clear();
        }
    }

    synchronized void drop(String token) {
        Player p = players.remove(token);
        if (p != null && p.out != null) p.out.close();
    }

    synchronized Player join(String token, String id, String name, Out out) {
        return join(token, id, name, null, out);
    }

    synchronized Player join(String token, String id, String name, String av, Out out) {
        Player old = players.remove(token);
        if (old != null && old.out != null) old.out.close();
        Player p = new Player(id, name, token, out, rnd.nextInt(360));
        p.av = av != null && av.length() > 0 ? av : "d" + (1 + rnd.nextInt(DEFAULT_AVATARS));
        spawn(p);
        if (specTokens.contains(token)) p.spec = p.dead = true;
        // a Battle Royale round already under way: watch until the next one
        if (cfg.gameMode == 1 && (phase == PH_RUN || phase == PH_END)) p.dead = true;
        players.put(token, p);
        return p;
    }

    private final java.util.Set<String> specTokens = new java.util.HashSet<String>();

    /** Host-only: a spectator is out of the arena and sees the whole map (zoomable). Returns false for an unknown player. */
    synchronized boolean setSpectator(String id, boolean on) {
        for (Player p : players.values()) {
            if (p.out == null || !p.id.equals(id)) continue;
            if (!on && !p.spec) return true;
            p.spec = on;
            if (on) {
                specTokens.add(p.token);
                p.dead = true;
                p.dx = p.dy = 0;
                p.fire = p.pending = p.boost = false;
                p.notes.add("You are now a spectator");
            } else {
                specTokens.remove(p.token);
                spawn(p);
                if (cfg.gameMode == 1 && (phase == PH_RUN || phase == PH_END)) p.dead = true;
                p.notes.add("You are back in the game");
            }
            return true;
        }
        return false;
    }

    synchronized void leave(Player p) {
        if (players.get(p.token) == p) players.remove(p.token);
    }

    /** The profile picture key others see: "d1".."d6" (built-in cats and dogs) or "c<version>" (uploaded). */
    synchronized void setAvatar(String token, String key) {
        Player p = players.get(token);
        if (p != null) p.av = key;
    }

    /**
     * Host-only super power. {@code id} is a player id, or null/"all" for every connected device.
     * @return how many players received it (0 for an unknown power)
     */
    synchronized int grant(String id, String power) {
        int idx = -1;
        for (int i = 0; i < POWERS.length; i++) if (POWERS[i].equals(power)) idx = i;
        if (idx < 0) return 0;
        int n = 0;
        for (Player p : players.values()) {
            if (p.out == null || (id != null && !id.equals("all") && !p.id.equals(id))) continue;
            switch (idx) {
                case 0: p.speedUntil = clock + 15000; break;
                case 1: p.rapidUntil = clock + 15000; break;
                case 2: p.powerUntil = clock + 15000; break;
                case 3:
                    p.hpMax = 150;
                    p.hp = 150;
                    break;
                default: p.invUntil = clock + 8000;
            }
            p.notes.add("The host gave you " + POWER_LABELS[idx]);
            n++;
        }
        return n;
    }

    void input(Player p, String s) {
        if (p.spec) return;
        try {
            if (s.startsWith("m ")) {
                String[] t = s.split(" ");
                p.dx = clamp(Double.parseDouble(t[1]));
                p.dy = clamp(Double.parseDouble(t[2]));
            } else if (s.startsWith("b ")) {
                p.boost = s.charAt(2) == '1';
            } else if (s.startsWith("a ")) {
                double a = Double.parseDouble(s.substring(2).trim());
                if (a == a && Math.abs(a) < 1000) p.aim = a;
            } else if (s.startsWith("f ")) {
                boolean on = s.charAt(2) == '1';
                p.fire = on;
                if (on) p.pending = true; // even a very short click fires once
            } else if (s.equals("r")) {
                synchronized (this) {
                    if (p.dead && players.get(p.token) == p && (cfg.gameMode != 1 || phase <= PH_COUNT) && !p.spec) spawn(p);
                }
            }
        } catch (RuntimeException ignored) {
            // malformed input is ignored
        }
    }

    private void respawnOrb(int i) {
        ox[i] = 20 + rnd.nextDouble() * (W - 40);
        oy[i] = 20 + rnd.nextDouble() * (H - 40);
        oh[i] = rnd.nextInt(360);
    }

    private void spawn(Player p) {
        p.r = START_R;
        p.hp = p.hpMax = HP;
        p.dead = false;
        p.dx = p.dy = 0;
        p.boost = false;
        p.fire = p.pending = false;
        p.speedUntil = p.rapidUntil = p.powerUntil = p.invUntil = 0;
        p.spawnUntil = clock + 2000;
        if (p.spec) p.dead = true;
        p.lastHurt = clock;
        if (cfg.gameMode == 1) { // Battle Royale: everyone starts inside the safe zone
            double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * (ZONE_R0 - 150);
            p.x = W / 2 + Math.cos(a) * d;
            p.y = H / 2 + Math.sin(a) * d;
        } else {
            p.x = 100 + rnd.nextDouble() * (W - 200);
            p.y = 100 + rnd.nextDouble() * (H - 200);
        }
    }

    private static int score(double r) {
        return Math.max(0, (int) ((r * r - START_R * START_R) / 10));
    }

    private boolean guarded(Player p) {
        return clock < p.invUntil || clock < p.spawnUntil;
    }

    private void fillBots(int humans) {
        int want = humans > 0 ? botFill : 0, have = 0; // one sparring bot; everyone else is a connected device
        for (Player p : players.values()) if (p.out == null) have++;
        while (have < want) {
            String id = "b" + (++botSeq);
            Player b = new Player(id, "Pride", id, null, rnd.nextInt(360));
            sinify(b);
            spawn(b);
            if (cfg.gameMode == 1 && (phase == PH_RUN || phase == PH_END)) b.dead = true;
            players.put(id, b);
            have++;
        }
        for (Iterator<Player> it = players.values().iterator(); it.hasNext() && have > want; ) {
            if (it.next().out == null) {
                it.remove();
                have--;
            }
        }
    }

    private void think(Player b, List<Player> ps) {
        if (--b.think > 0) return;
        b.think = 4 + rnd.nextInt(6);
        Player threat = null, prey = null, target = null;
        double td = 1e18, pd = 1e18, gd = 560;
        for (Player o : ps) {
            if (o == b || o.dead) continue;
            double d = Math.hypot(o.x - b.x, o.y - b.y);
            if (d < gd) {
                target = o;
                gd = d;
            }
            if (o.r > b.r * 1.12 && d < 330 + o.r && d < td) {
                threat = o;
                td = d;
            } else if (b.r > o.r * 1.2 && d < 380 && d < pd) {
                prey = o;
                pd = d;
            }
        }
        b.boost = false;
        if (target != null) { // shoot at the nearest enemy, not perfectly
            b.aim = Math.atan2(target.y - b.y, target.x - b.x) + (rnd.nextDouble() - 0.5) * 0.36;
            // bots keep a reserve of points, so they grow instead of spending everything on shots
            b.fire = gd < 650 && score(b.r) >= 12 && rnd.nextDouble() < 0.7;
        } else {
            b.fire = false;
        }
        double tx, ty;
        boolean outside = cfg.gameMode == 1 && phase == PH_RUN && Math.hypot(b.x - W / 2, b.y - H / 2) > zoneR - 90;
        if (outside) {
            tx = W / 2;
            ty = H / 2;
        } else if (threat != null) {
            tx = b.x + (b.x - threat.x);
            ty = b.y + (b.y - threat.y);
            b.boost = td < 220;
        } else if (prey != null) {
            tx = prey.x;
            ty = prey.y;
        } else {
            int best = -1;
            double bd = 450 * 450;
            for (int i = 0; i < ORBS; i++) {
                double d = (ox[i] - b.x) * (ox[i] - b.x) + (oy[i] - b.y) * (oy[i] - b.y);
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            if (best >= 0) {
                tx = ox[best];
                ty = oy[best];
            } else {
                tx = 100 + rnd.nextDouble() * (W - 200);
                ty = 100 + rnd.nextDouble() * (H - 200);
            }
        }
        double vx = tx - b.x, vy = ty - b.y;
        if (b.x < 120) vx += 300;
        if (b.x > W - 120) vx -= 300;
        if (b.y < 120) vy += 300;
        if (b.y > H - 120) vy -= 300;
        double len = Math.hypot(vx, vy);
        if (len < 1e-6) len = 1;
        b.dx = vx / len;
        b.dy = vy / len;
    }

    private void resetMatch() {
        phase = PH_WAIT;
        phaseAt = clock;
        zoneR = ZONE_R0;
        bullets.clear();
    }

    private void startRound() {
        bullets.clear();
        for (Player p : players.values()) {
            spawn(p);
            p.kills = 0;
            if (p.out == null) {
                sinify(p);
            }
        }
        zoneR = ZONE_R0;
        phase = PH_COUNT;
        phaseAt = clock;
    }

    /** Battle Royale rounds: countdown, shrinking zone, a winner, then again. */
    private void royale() {
        switch (phase) {
            case PH_WAIT:
                if (players.size() >= 2) startRound();
                break;
            case PH_COUNT:
                if (clock - phaseAt >= COUNT_MS) {
                    phase = PH_RUN;
                    phaseAt = clock;
                    events.add("[\"s\"]");
                }
                break;
            case PH_RUN: {
                double f = Math.min(1.0, (clock - phaseAt) / (double) SHRINK_MS);
                zoneR = ZONE_R0 - (ZONE_R0 - ZONE_R1) * f;
                int alive = 0;
                Player last = null;
                for (Player p : players.values()) if (!p.dead) {
                    alive++;
                    last = p;
                }
                if (alive <= 1) {
                    phase = PH_END;
                    phaseAt = clock;
                    events.add("[\"w\"," + Util.q(last == null ? "Nobody" : last.name) + "," + (last != null && last.out != null ? 1 : 0) + "]");
                }
                break;
            }
            default:
                if (clock - phaseAt >= END_MS) startRound();
        }
    }

    private void kill(Player v, Player by, String byName) {
        int sc = score(v.r);
        v.dead = true;
        v.boost = false;
        v.fire = v.pending = false;
        v.respawnAt = clock + 2500;
        if (by != null) {
            by.r = Math.sqrt(by.r * by.r + v.r * v.r * 0.7);
            by.kills++;
        }
        events.add("[\"k\"," + Util.q(byName) + "," + Util.q(v.name) + "," + Math.round(v.x) + "," + Math.round(v.y) + "," + v.hue + "]");
        if (v.out != null) outbox.add(new Pending(v, "{\"t\":\"dead\",\"by\":" + Util.q(byName) + ",\"score\":" + sc + ",\"kills\":" + v.kills + "}"));
    }

    private void damage(Player v, Player by, double dmg, double x, double y) {
        if (v.dead) return;
        if (guarded(v)) {
            events.add("[\"b\"," + Math.round(x) + "," + Math.round(y) + "]");
            return;
        }
        v.hp -= dmg;
        v.lastHurt = clock;
        events.add("[\"h\"," + Math.round(x) + "," + Math.round(y) + "," + (int) dmg + "," + Util.q(v.id) + "]");
        if (v.hp <= 0) {
            v.hp = 0;
            kill(v, by, by == null ? "the zone" : by.name);
        }
    }

    private void shoot(Player p, boolean live) {
        if (!live || p.dead) {
            p.pending = false;
            return;
        }
        if (!(p.fire || p.pending) || clock < p.nextShot) return;
        boolean rapid = clock < p.rapidUntil;
        if (!rapid) {
            if (score(p.r) < SHOT_COST) { // not enough points: the shot is lost
                p.pending = false;
                return;
            }
            p.r = Math.sqrt(p.r * p.r - SHOT_COST * 10);
        }
        double ax = Math.cos(p.aim), ay = Math.sin(p.aim);
        Bullet b = new Bullet();
        b.id = ++bulletSeq;
        b.px = b.x = p.x + ax * (p.r + 6);
        b.py = b.y = p.y + ay * (p.r + 6);
        b.vx = ax * BULLET_SPEED;
        b.vy = ay * BULLET_SPEED;
        b.life = BULLET_LIFE;
        b.owner = p;
        b.hue = p.hue;
        b.dmg = clock < p.powerUntil ? POWER_DAMAGE : SHOT_DAMAGE;
        bullets.add(b);
        p.nextShot = clock + (rapid ? 120 : 350);
        p.pending = false;
    }

    /** True when the segment from (x1,y1) to (x2,y2) passes within rad of (cx,cy). */
    private static boolean sweeps(double x1, double y1, double x2, double y2, double cx, double cy, double rad) {
        double vx = x2 - x1, vy = y2 - y1, l2 = vx * vx + vy * vy;
        double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((cx - x1) * vx + (cy - y1) * vy) / l2));
        double dx = x1 + vx * t - cx, dy = y1 + vy * t - cy;
        return dx * dx + dy * dy <= rad * rad;
    }

    private void stepBullets(List<Player> ps) {
        for (Iterator<Bullet> it = bullets.iterator(); it.hasNext(); ) {
            Bullet b = it.next();
            b.px = b.x;
            b.py = b.y;
            b.x += b.vx * DT;
            b.y += b.vy * DT;
            b.life -= DT;
            if (b.life <= 0 || b.x < 0 || b.y < 0 || b.x > W || b.y > H) {
                it.remove();
                continue;
            }
            for (Player q : ps) {
                if (q == b.owner || q.dead || !sweeps(b.px, b.py, b.x, b.y, q.x, q.y, q.r + 4)) continue;
                damage(q, b.owner, b.dmg, b.x, b.y);
                it.remove();
                break;
            }
        }
    }

    void tick() {
        final List<Player> to = new ArrayList<Player>();
        final List<String> msgs = new ArrayList<String>();
        final List<Boolean> critical = new ArrayList<Boolean>();
        synchronized (this) {
            if (!cfg.gameEnabled) {
                for (Player p : players.values()) if (p.out != null) p.out.close();
                players.clear();
                bullets.clear();
                return;
            }
            int humans = 0;
            for (Player p : players.values()) if (p.out != null) humans++;
            fillBots(humans);
            if (humans == 0) {
                players.clear();
                resetMatch();
                return;
            }
            clock += 50;
            int mode = cfg.gameMode == 1 ? 1 : 0;
            if (mode != lastMode) {
                lastMode = mode;
                resetMatch();
                for (Player p : players.values()) spawn(p);
            }
            events.clear();
            outbox.clear();
            List<Player> ps = new ArrayList<Player>(players.values());
            if (mode == 1) royale();
            boolean live = mode == 0 || phase == PH_RUN;
            for (Player p : ps) {
                if (p.dead) {
                    if (mode == 0 && p.out == null && clock >= p.respawnAt) {
                        sinify(p);
                        spawn(p);
                    }
                    continue;
                }
                if (p.out == null) think(p, ps);
                move(p);
                eatOrbs(p);
                shoot(p, live);
                if (mode == 0 && clock - p.lastHurt > 4000 && p.hp < p.hpMax) p.hp = Math.min(p.hpMax, p.hp + 2 * DT);
                if (mode == 1 && phase == PH_RUN && !guarded(p) && Math.hypot(p.x - W / 2, p.y - H / 2) > zoneR) damage(p, null, ZONE_DPS * DT, p.x, p.y);
            }
            stepBullets(ps);
            if (live) {
                for (Player a : ps) {
                    if (a.dead) continue;
                    for (Player b : ps) {
                        if (a == b || b.dead || guarded(b)) continue;
                        double d = Math.hypot(a.x - b.x, a.y - b.y);
                        if (a.r > b.r * 1.15 && d < a.r - b.r * 0.35) kill(b, a, a.name);
                    }
                }
            }
            for (Pending o : outbox) {
                to.add(o.to);
                critical.add(Boolean.TRUE);
                msgs.add(o.msg);
            }
            String lb = leaderboard(ps), mm = minimap(ps), ev = eventsJson();
            for (Player p : ps) {
                if (p.out == null) continue;
                to.add(p);
                critical.add(Boolean.FALSE);
                msgs.add(snapshot(p, ps, lb, mm, ev));
            }
        }
        for (int i = 0; i < to.size(); i++) deliver(to.get(i), msgs.get(i), critical.get(i));
    }

    private String eventsJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < events.size() && i < 40; i++) {
            if (i > 0) sb.append(',');
            sb.append(events.get(i));
        }
        return sb.append(']').toString();
    }

    private void move(Player p) {
        double dx = p.dx, dy = p.dy, len = Math.sqrt(dx * dx + dy * dy);
        if (len > 0.05) {
            double k = Math.min(len, 1.0) / len;
            double sp = Math.max(70, 230 * Math.pow(START_R / p.r, 0.35));
            if (clock < p.speedUntil) sp *= 1.6;
            if (p.boost && p.r > MIN_BOOST_R) {
                sp *= 1.7;
                p.r = Math.sqrt(Math.max(MIN_BOOST_R * MIN_BOOST_R, p.r * p.r - 1.6));
            }
            p.x += dx * k * sp * DT;
            p.y += dy * k * sp * DT;
        }
        p.x = Math.max(p.r, Math.min(W - p.r, p.x));
        p.y = Math.max(p.r, Math.min(H - p.r, p.y));
        double base = START_R * START_R;
        p.r = Math.sqrt(base + (p.r * p.r - base) * (1 - 0.006 * DT));
    }

    private void eatOrbs(Player p) {
        double reach = (p.r + 3) * (p.r + 3);
        for (int i = 0; i < ORBS; i++) {
            double ex = ox[i] - p.x, ey = oy[i] - p.y;
            if (ex * ex + ey * ey < reach) {
                p.r = Math.sqrt(p.r * p.r + 10);
                respawnOrb(i);
            }
        }
    }

    private String leaderboard(List<Player> ps) {
        List<Player> alive = new ArrayList<Player>();
        for (Player p : ps) if (!p.dead) alive.add(p);
        Collections.sort(alive, new Comparator<Player>() {
            @Override public int compare(Player a, Player b) {
                return Double.compare(b.r, a.r);
            }
        });
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < alive.size() && i < 5; i++) {
            Player p = alive.get(i);
            if (i > 0) sb.append(',');
            sb.append('[').append(Util.q(p.id)).append(',').append(Util.q(p.name)).append(',').append(score(p.r)).append(',')
                    .append(p.kills).append(',').append(Util.q(effAv(p))).append(']');
        }
        return sb.append(']').toString();
    }

    private String minimap(List<Player> ps) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Player p : ps) {
            if (p.dead) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(Util.q(p.id)).append(',').append(Math.round(p.x)).append(',').append(Math.round(p.y)).append(',').append(Math.round(p.r)).append(']');
        }
        return sb.append(']').toString();
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** What others see: an active blessing swaps the avatar for a matching pixel-art hero (bots keep their sin). */
    private String effAv(Player p) {
        if (p.out == null) return p.av;
        if (clock < p.invUntil) return "b5";
        if (p.hpMax > HP) return "b4";
        if (clock < p.powerUntil) return "b3";
        if (clock < p.rapidUntil) return "b2";
        if (clock < p.speedUntil) return "b1";
        return p.av;
    }

    private int powerBits(Player p) {
        return (clock < p.speedUntil ? 1 : 0) | (clock < p.rapidUntil ? 2 : 0) | (clock < p.powerUntil ? 4 : 0)
                | (p.hpMax > HP ? 8 : 0) | (clock < p.invUntil ? 16 : 0) | (clock < p.spawnUntil ? 32 : 0);
    }

    private String snapshot(Player me, List<Player> ps, String lb, String mm, String ev) {
        double cx = me.x, cy = me.y;
        boolean watching = me.dead && cfg.gameMode == 1;
        if (me.dead) { // eliminated: follow the biggest survivor, or stay where we fell
            Player t = null;
            for (Player p : ps) if (!p.dead && (t == null || p.r > t.r)) t = p;
            if (t != null) {
                cx = t.x;
                cy = t.y;
            }
        }
        double view = me.spec ? 100000 : me.dead ? 1000 : Math.min(1500, 520 + me.r * 6);
        StringBuilder sb = new StringBuilder(2560).append("{\"t\":\"s\",\"p\":[");
        boolean first = true;
        for (Player p : ps) {
            if (p.dead || Math.abs(p.x - cx) > view + p.r || Math.abs(p.y - cy) > view + p.r) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(Util.q(p.id)).append(',').append(Util.q(p.name)).append(',')
                    .append(round(p.x)).append(',').append(round(p.y)).append(',').append(round(p.r)).append(',').append(p.hue).append(',')
                    .append(round2(p.aim)).append(',').append(Math.round(p.hp)).append(',').append(Util.q(effAv(p))).append(',').append(powerBits(p)).append(']');
        }
        sb.append("],\"o\":[");
        first = true;
        for (int i = 0; i < ORBS; i++) {
            if (Math.abs(ox[i] - cx) > view || Math.abs(oy[i] - cy) > view) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(Math.round(ox[i])).append(',').append(Math.round(oy[i])).append(',').append(oh[i]).append(']');
        }
        sb.append("],\"b\":[");
        first = true;
        for (Bullet b : bullets) {
            if (Math.abs(b.x - cx) > view || Math.abs(b.y - cy) > view) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(b.id).append(',').append(Math.round(b.x)).append(',').append(Math.round(b.y)).append(',').append(b.hue).append(',').append(b.dmg > SHOT_DAMAGE ? 1 : 0).append(']');
        }
        int alive = 0;
        for (Player p : ps) if (!p.dead) alive++;
        long left = phase == PH_COUNT ? COUNT_MS - (clock - phaseAt) : phase == PH_END ? END_MS - (clock - phaseAt)
                : phase == PH_RUN ? SHRINK_MS - (clock - phaseAt) : 0;
        sb.append("],\"ev\":").append(ev)
                .append(",\"g\":{\"m\":").append(cfg.gameMode == 1 ? 1 : 0).append(",\"ph\":").append(phase).append(",\"t\":").append(Math.max(0, left / 1000))
                .append(",\"zr\":").append(Math.round(zoneR)).append(",\"al\":").append(alive).append('}')
                .append(",\"me\":[").append(Math.round(me.hp)).append(',').append(Math.round(me.hpMax)).append(',').append(score(me.r)).append(',').append(me.kills)
                .append(',').append(Math.max(0, me.speedUntil - clock)).append(',').append(Math.max(0, me.rapidUntil - clock)).append(',')
                .append(Math.max(0, me.powerUntil - clock)).append(',').append(Math.max(0, me.invUntil - clock)).append(',').append(watching ? 1 : 0).append(',').append(me.spec ? 1 : 0).append(']')
                .append(",\"cx\":").append(Math.round(cx)).append(",\"cy\":").append(Math.round(cy));
        if (!me.notes.isEmpty()) {
            sb.append(",\"nt\":[");
            for (int i = 0; i < me.notes.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(Util.q(me.notes.get(i)));
            }
            sb.append(']');
            me.notes.clear();
        }
        return sb.append(",\"lb\":").append(lb).append(",\"mm\":").append(mm)
                .append(",\"n\":").append(ps.size()).append('}').toString();
    }

    private void deliver(final Player p, final String msg, final boolean critical) {
        final Out out = p.out;
        if (out.isDead()) return;
        // a slow client drops snapshots, but never the one message that says it was eaten
        final boolean owns = !critical && p.busy.compareAndSet(false, true);
        if (!critical && !owns) return;
        try {
            io.execute(new Runnable() {
                @Override public void run() {
                    try {
                        out.send(msg);
                    } catch (IOException e) {
                        out.close();
                    } finally {
                        if (owns) p.busy.set(false);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            if (owns) p.busy.set(false);
        }
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double clamp(double v) {
        return v != v ? 0 : Math.max(-1, Math.min(1, v));
    }

    /** Runs the WebSocket session on the calling thread until it closes. */
    void run(String token, String id, String name, String av, Socket sock, InputStream in, OutputStream out) {
        Client c = new Client(sock, out);
        if (!cfg.gameEnabled) {
            try {
                c.frame(8, new byte[0]);
            } catch (IOException ignored) {
            }
            return;
        }
        Player p = join(token, id, name, av, c);
        try {
            sock.setSoTimeout(600000); // the page pings every 15 s; a tab hidden for 5+ min is throttled to about one ping a minute
            c.send("{\"t\":\"hello\",\"id\":" + Util.q(id) + ",\"w\":" + (int) W + ",\"h\":" + (int) H + ",\"r0\":" + (int) START_R + ",\"cost\":" + SHOT_COST + ",\"mode\":" + (cfg.gameMode == 1 ? 1 : 0) + "}");
            readLoop(p, in, c);
        } catch (IOException ignored) {
        } finally {
            leave(p);
            c.close();
        }
    }

    private void readLoop(Player p, InputStream in, Client c) throws IOException {
        while (!c.dead) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) return;
            int op = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) len = (in.read() << 8) | in.read();
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
            }
            if (len < 0 || len > 4096) return;
            byte[] mask = new byte[4];
            if (masked) readFully(in, mask);
            byte[] d = new byte[(int) len];
            readFully(in, d);
            if (masked) for (int i = 0; i < d.length; i++) d[i] ^= mask[i & 3];
            if (op == 8) return;
            if (op == 9) c.frame(10, d);
            else if (op == 1) input(p, new String(d, UTF8));
        }
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int o = 0;
        while (o < b.length) {
            int n = in.read(b, o, b.length - o);
            if (n < 0) throw new IOException("eof");
            o += n;
        }
    }

    synchronized double[] stateOf(Player p) {
        return new double[]{p.x, p.y, p.r, p.dead ? 1 : 0};
    }

    synchronized void place(Player p, double x, double y, double r) {
        p.x = x;
        p.y = y;
        p.r = r;
    }

    synchronized double[] orb(int i) {
        return new double[]{ox[i], oy[i]};
    }

    synchronized double hpOf(Player p) {
        return p.hp;
    }

    /** Connected devices (not the bot) for the host's power panel. */
    synchronized String rosterJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Player p : players.values()) {
            if (p.out == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(Util.q(p.id)).append(",\"name\":").append(Util.q(p.name)).append(",\"av\":").append(Util.q(p.av))
                    .append(",\"hp\":").append(Math.round(p.hp)).append(",\"score\":").append(score(p.r)).append(",\"dead\":").append(p.dead).append(",\"spec\":").append(p.spec).append('}');
        }
        return sb.append(']').toString();
    }

    synchronized int phaseOf() {
        return phase;
    }

    synchronized int bulletCount() {
        return bullets.size();
    }

    synchronized double zone() {
        return zoneR;
    }

    synchronized List<String> botNames() {
        List<String> l = new ArrayList<String>();
        for (Player p : players.values()) if (p.out == null) l.add(p.name);
        return l;
    }

    synchronized int botCount() {
        int n = 0;
        for (Player p : players.values()) if (p.out == null) n++;
        return n;
    }

    synchronized double maxBotRadius() {
        double m = 0;
        for (Player p : players.values()) if (p.out == null && !p.dead) m = Math.max(m, p.r);
        return m;
    }

    synchronized void parkOrbs() {
        for (int i = 0; i < ORBS; i++) {
            ox[i] = 5;
            oy[i] = 5;
        }
    }
}
