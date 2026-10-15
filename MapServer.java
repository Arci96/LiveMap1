package de.livemap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MapServer {

    private static final int MAX_MOBS = 3000;

    private final LiveMapPlugin plugin;
    private final TileStore store;
    private final String bind;
    private final int port;
    private final String token;
    private HttpServer http;
    private ExecutorService pool;
    private byte[] indexHtml;

    public MapServer(LiveMapPlugin plugin, TileStore store, String bind, int port, String token) {
        this.plugin = plugin;
        this.store = store;
        this.bind = bind;
        this.port = port;
        this.token = token;
    }

    public void start() throws IOException {
        try (InputStream in = plugin.getResource("index.html")) {
            indexHtml = in.readAllBytes();
        }
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "LiveMap-HTTP");
            t.setDaemon(true);
            return t;
        });
        http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        http.createContext("/", this::handle);
        http.setExecutor(pool);
        http.start();
    }

    public void stop() {
        if (http != null) http.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    // ---------- HTTP ----------

    private void handle(HttpExchange ex) throws IOException {
        try {
            URI uri = ex.getRequestURI();
            Map<String, String> q = parse(uri.getRawQuery());
            if (!token.isEmpty() && !token.equals(q.get("token"))) {
                send(ex, 403, "text/plain; charset=utf-8", "Zugriff verweigert: Token fehlt oder falsch.");
                return;
            }
            switch (uri.getPath()) {
                case "/" -> send(ex, 200, "text/html; charset=utf-8", indexHtml);
                case "/api/state" -> send(ex, 200, "application/json", state());
                case "/api/events" -> send(ex, 200, "application/json", events(q));
                case "/api/chunks" -> send(ex, 200, "application/json", chunks(q));
                case "/api/region" -> region(ex, q);
                default -> send(ex, 404, "text/plain; charset=utf-8", "Nicht gefunden");
            }
        } catch (Exception e) {
            send(ex, 500, "text/plain; charset=utf-8", "Fehler: " + e);
        } finally {
            ex.close();
        }
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        send(ex, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                  URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static int num(Map<String, String> q, String key) {
        try {
            return Integer.parseInt(q.getOrDefault(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // Bukkit-API darf nur im Hauptthread benutzt werden
    private <T> T sync(Callable<T> c) throws Exception {
        return Bukkit.getScheduler().callSyncMethod(plugin, c).get(5, TimeUnit.SECONDS);
    }

    private static String js(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String d(double v) {
        return Double.toString(Math.round(v * 10) / 10.0);
    }

    // ---------- /api/state : Welten, Spieler, Mobs ----------

    private String state() throws Exception {
        return sync(() -> {
            StringBuilder sb = new StringBuilder(8192);
            sb.append("{\"tps\":").append(d(Math.min(20.0, Bukkit.getTPS()[0])));

            sb.append(",\"worlds\":[");
            boolean first = true;
            for (World w : Bukkit.getWorlds()) {
                if (!first) sb.append(',');
                first = false;
                WorldBorder b = w.getWorldBorder();
                Location sp = w.getSpawnLocation();
                sb.append("{\"name\":").append(js(w.getName()))
                  .append(",\"env\":").append(js(w.getEnvironment().name()))
                  .append(",\"time\":").append(w.getTime())
                  .append(",\"storm\":").append(w.hasStorm())
                  .append(",\"chunks\":").append(w.getChunkCount())
                  .append(",\"sx\":").append(sp.getBlockX())
                  .append(",\"sz\":").append(sp.getBlockZ())
                  .append(",\"bx\":").append(d(b.getCenter().getX()))
                  .append(",\"bz\":").append(d(b.getCenter().getZ()))
                  .append(",\"bs\":").append(d(b.getSize()))
                  .append('}');
            }

            sb.append("],\"players\":[");
            first = true;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!first) sb.append(',');
                first = false;
                Location l = p.getLocation();
                sb.append("{\"name\":").append(js(p.getName()))
                  .append(",\"w\":").append(js(l.getWorld().getName()))
                  .append(",\"x\":").append(d(l.getX()))
                  .append(",\"y\":").append(d(l.getY()))
                  .append(",\"z\":").append(d(l.getZ()))
                  .append(",\"yaw\":").append(d(l.getYaw()))
                  .append(",\"hp\":").append(d(p.getHealth()))
                  .append(",\"gm\":").append(js(p.getGameMode().name()))
                  .append('}');
            }

            sb.append("],\"mobs\":[");
            first = true;
            int count = 0;
            outer:
            for (World w : Bukkit.getWorlds()) {
                String wn = js(w.getName());
                for (LivingEntity e : w.getLivingEntities()) {
                    if (e instanceof Player || e instanceof ArmorStand) continue;
                    if (count++ >= MAX_MOBS) break outer;
                    if (!first) sb.append(',');
                    first = false;
                    Location l = e.getLocation();
                    sb.append("{\"t\":").append(js(e.getType().name()))
                      .append(",\"h\":").append(e instanceof Enemy ? 1 : 0)
                      .append(",\"w\":").append(wn)
                      .append(",\"x\":").append(d(l.getX()))
                      .append(",\"y\":").append(d(l.getY()))
                      .append(",\"z\":").append(d(l.getZ()))
                      .append('}');
                }
            }
            return sb.append("]}").toString();
        });
    }

    // ---------- /api/events : platzierte/abgebaute Bloecke ----------

    private String events(Map<String, String> q) {
        long since;
        try {
            since = Long.parseLong(q.getOrDefault("since", "0"));
        } catch (NumberFormatException e) {
            since = 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"last\":").append(plugin.lastEventId()).append(",\"events\":[");
        boolean first = true;
        for (LiveMapPlugin.BlockEvent e : plugin.eventsSince(since)) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(e.id())
              .append(",\"t\":").append(e.time())
              .append(",\"type\":").append(js(e.type()))
              .append(",\"p\":").append(js(e.player()))
              .append(",\"b\":").append(js(e.block()))
              .append(",\"w\":").append(js(e.world()))
              .append(",\"x\":").append(e.x())
              .append(",\"y\":").append(e.y())
              .append(",\"z\":").append(e.z())
              .append('}');
        }
        return sb.append("]}").toString();
    }

    // ---------- /api/chunks : Draufsicht (live oder aus dem Speicher) ----------

    private record Snap(int x, int z, ChunkSnapshot snap) {}

    private static long ck(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static boolean appendChunk(StringBuilder sb, boolean first, int x, int z, int[] px, int live) {
        if (!first) sb.append(',');
        sb.append("{\"x\":").append(x).append(",\"z\":").append(z).append(",\"l\":").append(live).append(",\"d\":[");
        for (int i = 0; i < 256; i++) {
            if (i > 0) sb.append(',');
            sb.append(px[i]);
        }
        sb.append("]}");
        return false;
    }

    private String chunks(Map<String, String> q) throws Exception {
        World w = Bukkit.getWorld(q.getOrDefault("world", ""));
        if (w == null) return "{\"chunks\":[]}";

        final int x0 = num(q, "x0");
        final int z0 = num(q, "z0");
        final int x1 = Math.min(num(q, "x1"), x0 + 7);   // max. 8x8 Chunks pro Anfrage
        final int z1 = Math.min(num(q, "z1"), z0 + 7);

        List<Snap> snaps = sync(() -> {
            List<Snap> list = new ArrayList<>();
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    if (w.isChunkLoaded(cx, cz)) {
                        list.add(new Snap(cx, cz, w.getChunkAt(cx, cz, false).getChunkSnapshot(true, false, false)));
                    }
                }
            }
            return list;
        });

        boolean nether = w.getEnvironment() == World.Environment.NETHER;
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();
        String wn = w.getName();

        StringBuilder sb = new StringBuilder(64 * 1700 + 32);
        sb.append("{\"chunks\":[");
        boolean first = true;
        Set<Long> live = new HashSet<>();

        for (Snap s : snaps) {                       // geladene Chunks: live rendern + speichern
            live.add(ck(s.x(), s.z()));
            int[] px = ChunkRenderer.render(s.snap(), nether, minY, maxY);
            store.put(wn, s.x(), s.z(), px);
            first = appendChunk(sb, first, s.x(), s.z(), px, 1);
        }
        for (int cx = x0; cx <= x1; cx++) {          // alle anderen: aus dem Speicher
            for (int cz = z0; cz <= z1; cz++) {
                if (live.contains(ck(cx, cz))) continue;
                int[] px = store.getChunk(wn, cx, cz);
                if (px != null) first = appendChunk(sb, first, cx, cz, px, 0);
            }
        }
        return sb.append("]}").toString();
    }

    // ---------- /api/region : verkleinerte Uebersicht einer Region (512x512 Bloecke) ----------

    private void region(HttpExchange ex, Map<String, String> q) throws IOException {
        int f = Integer.highestOneBit(Math.max(2, Math.min(32, num(q, "f"))));
        byte[] data = store.overview(q.getOrDefault("world", ""), num(q, "rx"), num(q, "rz"), f);
        if (data == null) {
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(204, -1);
            return;
        }
        send(ex, 200, "application/octet-stream", data);
    }
}
