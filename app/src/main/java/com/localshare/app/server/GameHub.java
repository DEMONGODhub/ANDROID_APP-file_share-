package com.localshare.app.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
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
 * "Orb Arena": server-authoritative multiplayer game. Everyone steers a blob, eats orbs to grow,
 * and bigger blobs eat smaller ones. 20 ticks/second, state broadcast over WebSocket.
 */
final class GameHub {
    static final double W = 1200, H = 1200;
    static final int ORBS = 90;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** One WebSocket connection. */
    static final class Client {
        final Socket sock;
        final OutputStream out;
        final AtomicBoolean sending = new AtomicBoolean();
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

        void send(String text) throws IOException {
            frame(1, text.getBytes(UTF8));
        }

        void close() {
            dead = true;
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class Player {
        final String id, name, token;
        final Client c;
        final int hue;
        double x, y, r = 14;
        volatile double dx, dy;

        Player(String id, String name, String token, Client c, int hue) {
            this.id = id;
            this.name = name;
            this.token = token;
            this.c = c;
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

    GameHub(ServerConfig cfg, Executor io) {
        this.cfg = cfg;
        this.io = io;
        for (int i = 0; i < ORBS; i++) respawnOrb(i);
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
                } catch (Throwable ignored) {
                }
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
    }

    void shutdown() {
        sched.shutdownNow();
        synchronized (this) {
            for (Player p : players.values()) p.c.close();
            players.clear();
        }
    }

    /** Force-disconnect a player (kick / block). */
    synchronized void drop(String token) {
        Player p = players.remove(token);
        if (p != null) p.c.close();
    }

    private void respawnOrb(int i) {
        ox[i] = 10 + rnd.nextDouble() * (W - 20);
        oy[i] = 10 + rnd.nextDouble() * (H - 20);
        oh[i] = rnd.nextInt(360);
    }

    private void spawn(Player p) {
        p.r = 14;
        p.x = 60 + rnd.nextDouble() * (W - 120);
        p.y = 60 + rnd.nextDouble() * (H - 120);
    }

    private void tick() {
        final String msg;
        final List<Client> cs;
        synchronized (this) {
            if (players.isEmpty()) return;
            final double dt = 0.05;
            List<Player> ps = new ArrayList<Player>(players.values());
            for (Player p : ps) {
                double dx = p.dx, dy = p.dy;
                double len = Math.sqrt(dx * dx + dy * dy);
                if (len > 0.05) {
                    double k = Math.min(len, 1.0) / len;
                    double sp = 190 * Math.pow(14.0 / p.r, 0.4);
                    p.x += dx * k * sp * dt;
                    p.y += dy * k * sp * dt;
                }
                p.x = Math.max(p.r, Math.min(W - p.r, p.x));
                p.y = Math.max(p.r, Math.min(H - p.r, p.y));
                p.r = 14 + (p.r - 14) * (1 - 0.01 * dt); // slow shrink keeps the game moving
                for (int i = 0; i < ORBS; i++) {
                    double ex = ox[i] - p.x, ey = oy[i] - p.y;
                    if (ex * ex + ey * ey < p.r * p.r) {
                        p.r = Math.sqrt(p.r * p.r + 9);
                        respawnOrb(i);
                    }
                }
            }
            for (Player a : ps) {
                for (Player b : ps) {
                    if (a == b) continue;
                    double ex = a.x - b.x, ey = a.y - b.y;
                    double d = Math.sqrt(ex * ex + ey * ey);
                    if (a.r > b.r * 1.15 && d < a.r - b.r * 0.3) {
                        a.r = Math.sqrt(a.r * a.r + b.r * b.r * 0.6);
                        spawn(b);
                    }
                }
            }
            StringBuilder sb = new StringBuilder(2048).append("{\"t\":\"s\",\"p\":[");
            boolean first = true;
            for (Player p : ps) {
                if (!first) sb.append(',');
                first = false;
                sb.append('[').append(Util.q(p.id)).append(',').append(Util.q(p.name)).append(',')
                        .append(round(p.x)).append(',').append(round(p.y)).append(',').append(round(p.r)).append(',').append(p.hue).append(']');
            }
            sb.append("],\"o\":[");
            for (int i = 0; i < ORBS; i++) {
                if (i > 0) sb.append(',');
                sb.append('[').append(round(ox[i])).append(',').append(round(oy[i])).append(',').append(oh[i]).append(']');
            }
            sb.append("]}");
            msg = sb.toString();
            cs = new ArrayList<Client>();
            for (Player p : ps) cs.add(p.c);
        }
        for (final Client c : cs) {
            if (c.dead || !c.sending.compareAndSet(false, true)) continue; // slow client: drop this frame
            try {
                io.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            c.send(msg);
                        } catch (IOException e) {
                            c.close();
                        } finally {
                            c.sending.set(false);
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                c.sending.set(false);
            }
        }
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
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
        Player p = new Player(id, name, token, c, rnd.nextInt(360));
        synchronized (this) {
            Player old = players.remove(token);
            if (old != null) old.c.close();
            spawn(p);
            players.put(token, p);
        }
        try {
            sock.setSoTimeout(60000); // client pings every ~20s
            c.send("{\"t\":\"hello\",\"id\":" + Util.q(id) + ",\"w\":" + (int) W + ",\"h\":" + (int) H + "}");
            readLoop(p, in, c);
        } catch (IOException ignored) {
        } finally {
            synchronized (this) {
                if (players.get(token) == p) players.remove(token);
            }
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
            if (op == 9) {
                c.frame(10, d);
            } else if (op == 1) {
                String s = new String(d, UTF8);
                if (s.startsWith("m ")) {
                    try {
                        String[] t = s.split(" ");
                        p.dx = clamp(Double.parseDouble(t[1]));
                        p.dy = clamp(Double.parseDouble(t[2]));
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
    }

    private static double clamp(double v) {
        return v != v ? 0 : Math.max(-1, Math.min(1, v));
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int o = 0;
        while (o < b.length) {
            int n = in.read(b, o, b.length - o);
            if (n < 0) throw new IOException("eof");
            o += n;
        }
    }
}
