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
 * eats orbs to grow, boosts at the cost of size, and eats smaller blobs. Bots fill the arena so a
 * single player is never alone.
 */
final class GameHub {
    static final double W = 3000, H = 3000, START_R = 18, MIN_BOOST_R = 22;
    static final int ORBS = 220, BOT_FILL = 5;
    private static final double DT = 0.05;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String[] BOT_NAMES = {"Imp", "Wraith", "Ember", "Gloom", "Specter", "Ash", "Fiend", "Hex", "Cinder", "Shade"};

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
        final String id, name, token;
        final Out out; // null for bots
        final int hue;
        final AtomicBoolean busy = new AtomicBoolean();
        double x, y, r = START_R;
        boolean dead;
        long respawnAt;
        volatile double dx, dy;
        volatile boolean boost;
        int think;

        Player(String id, String name, String token, Out out, int hue) {
            this.id = id;
            this.name = name;
            this.token = token;
            this.out = out;
            this.hue = hue;
        }
    }

    private final ServerConfig cfg;
    private final Executor io;
    private final Random rnd = new Random();
    private final Map<String, Player> players = new LinkedHashMap<String, Player>(); // by session token; guarded by this
    private final double[] ox = new double[ORBS], oy = new double[ORBS];
    private final int[] oh = new int[ORBS];
    private final ScheduledExecutorService sched;
    private int botSeq;
    int botFill = BOT_FILL;

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
        Player old = players.remove(token);
        if (old != null && old.out != null) old.out.close();
        Player p = new Player(id, name, token, out, rnd.nextInt(360));
        spawn(p);
        players.put(token, p);
        return p;
    }

    synchronized void leave(Player p) {
        if (players.get(p.token) == p) players.remove(p.token);
    }

    void input(Player p, String s) {
        if (s.startsWith("m ")) {
            try {
                String[] t = s.split(" ");
                p.dx = clamp(Double.parseDouble(t[1]));
                p.dy = clamp(Double.parseDouble(t[2]));
            } catch (RuntimeException ignored) {
                // malformed input is ignored
            }
        } else if (s.startsWith("b ")) {
            p.boost = s.charAt(2) == '1';
        } else if (s.equals("r")) {
            synchronized (this) {
                if (p.dead && players.get(p.token) == p) spawn(p);
            }
        }
    }

    private void respawnOrb(int i) {
        ox[i] = 20 + rnd.nextDouble() * (W - 40);
        oy[i] = 20 + rnd.nextDouble() * (H - 40);
        oh[i] = rnd.nextInt(360);
    }

    private void spawn(Player p) {
        p.r = START_R;
        p.dead = false;
        p.dx = p.dy = 0;
        p.boost = false;
        p.x = 100 + rnd.nextDouble() * (W - 200);
        p.y = 100 + rnd.nextDouble() * (H - 200);
    }

    private static int score(double r) {
        return Math.max(0, (int) ((r * r - START_R * START_R) / 10));
    }

    private void fillBots(int humans) {
        int want = Math.max(0, botFill - humans), have = 0;
        for (Player p : players.values()) if (p.out == null) have++;
        while (have < want) {
            String id = "b" + (++botSeq);
            Player b = new Player(id, BOT_NAMES[rnd.nextInt(BOT_NAMES.length)], id, null, rnd.nextInt(360));
            spawn(b);
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
        Player threat = null, prey = null;
        double td = 1e18, pd = 1e18;
        for (Player o : ps) {
            if (o == b || o.dead) continue;
            double d = Math.hypot(o.x - b.x, o.y - b.y);
            if (o.r > b.r * 1.12 && d < 330 + o.r && d < td) {
                threat = o;
                td = d;
            } else if (b.r > o.r * 1.2 && d < 380 && d < pd) {
                prey = o;
                pd = d;
            }
        }
        b.boost = false;
        double tx, ty;
        if (threat != null) {
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

    void tick() {
        final List<Player> to = new ArrayList<Player>();
        final List<String> msgs = new ArrayList<String>();
        final List<Boolean> critical = new ArrayList<Boolean>();
        synchronized (this) {
            if (!cfg.gameEnabled) {
                for (Player p : players.values()) if (p.out != null) p.out.close();
                players.clear();
                return;
            }
            int humans = 0;
            for (Player p : players.values()) if (p.out != null) humans++;
            fillBots(humans);
            if (humans == 0) {
                players.clear();
                return;
            }
            long now = System.currentTimeMillis();
            List<Player> ps = new ArrayList<Player>(players.values());
            for (Player p : ps) {
                if (p.dead) {
                    if (p.out == null && now >= p.respawnAt) spawn(p);
                    continue;
                }
                if (p.out == null) think(p, ps);
                move(p);
                eatOrbs(p);
            }
            for (Player a : ps) {
                if (a.dead) continue;
                for (Player b : ps) {
                    if (a == b || b.dead) continue;
                    double d = Math.hypot(a.x - b.x, a.y - b.y);
                    if (a.r > b.r * 1.15 && d < a.r - b.r * 0.35) {
                        int sc = score(b.r);
                        a.r = Math.sqrt(a.r * a.r + b.r * b.r * 0.7);
                        b.dead = true;
                        b.boost = false;
                        b.respawnAt = now + 2500;
                        if (b.out != null) {
                            to.add(b);
                            critical.add(Boolean.TRUE);
                            msgs.add("{\"t\":\"dead\",\"by\":" + Util.q(a.name) + ",\"score\":" + sc + "}");
                        }
                    }
                }
            }
            String lb = leaderboard(ps), mm = minimap(ps);
            for (Player p : ps) {
                if (p.out == null) continue;
                to.add(p);
                critical.add(Boolean.FALSE);
                msgs.add(snapshot(p, ps, lb, mm));
            }
        }
        for (int i = 0; i < to.size(); i++) deliver(to.get(i), msgs.get(i), critical.get(i));
    }

    private void move(Player p) {
        double dx = p.dx, dy = p.dy, len = Math.sqrt(dx * dx + dy * dy);
        if (len > 0.05) {
            double k = Math.min(len, 1.0) / len;
            double sp = Math.max(70, 230 * Math.pow(START_R / p.r, 0.35));
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
            sb.append('[').append(Util.q(p.id)).append(',').append(Util.q(p.name)).append(',').append(score(p.r)).append(']');
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

    private String snapshot(Player me, List<Player> ps, String lb, String mm) {
        double view = Math.min(1500, 520 + me.r * 6);
        StringBuilder sb = new StringBuilder(2048).append("{\"t\":\"s\",\"p\":[");
        boolean first = true;
        for (Player p : ps) {
            if (p.dead || Math.abs(p.x - me.x) > view + p.r || Math.abs(p.y - me.y) > view + p.r) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(Util.q(p.id)).append(',').append(Util.q(p.name)).append(',')
                    .append(round(p.x)).append(',').append(round(p.y)).append(',').append(round(p.r)).append(',').append(p.hue).append(']');
        }
        sb.append("],\"o\":[");
        first = true;
        for (int i = 0; i < ORBS; i++) {
            if (Math.abs(ox[i] - me.x) > view || Math.abs(oy[i] - me.y) > view) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(Math.round(ox[i])).append(',').append(Math.round(oy[i])).append(',').append(oh[i]).append(']');
        }
        return sb.append("],\"lb\":").append(lb).append(",\"mm\":").append(mm)
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
    void run(String token, String id, String name, Socket sock, InputStream in, OutputStream out) {
        Client c = new Client(sock, out);
        if (!cfg.gameEnabled) {
            try {
                c.frame(8, new byte[0]);
            } catch (IOException ignored) {
            }
            return;
        }
        Player p = join(token, id, name, c);
        try {
            sock.setSoTimeout(60000); // the page pings every ~20s
            c.send("{\"t\":\"hello\",\"id\":" + Util.q(id) + ",\"w\":" + (int) W + ",\"h\":" + (int) H + ",\"r0\":" + (int) START_R + "}");
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
