package de.livemap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Rendert alle bereits generierten Chunks einer Welt nach und nach in den Kachel-Speicher. */
public class FullRender {

    private final LiveMapPlugin plugin;
    private final TileStore store;

    private World world;
    private long[] queue = new long[0];
    private int head, total, inFlight, done, ticks;
    private BukkitTask task;
    private CommandSender sender;
    private long startMs;

    public FullRender(LiveMapPlugin plugin, TileStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public boolean running() {
        return task != null;
    }

    private static File regionDir(World w) {
        File root = w.getWorldFolder();
        List<File> candidates = new ArrayList<>();
        switch (w.getEnvironment()) {
            case NETHER -> {
                candidates.add(new File(root, "DIM-1/region"));
                candidates.add(new File(root, "dimensions/minecraft/the_nether/region"));
            }
            case THE_END -> {
                candidates.add(new File(root, "DIM1/region"));
                candidates.add(new File(root, "dimensions/minecraft/the_end/region"));
            }
            default -> candidates.add(new File(root, "dimensions/minecraft/overworld/region"));
        }
        candidates.add(0, new File(root, "region"));
        for (File c : candidates) if (c.isDirectory()) return c;
        return null;
    }

    private record Reg(File file, int rx, int rz, double dist) {}

    public String start(World w, boolean force, CommandSender who) {
        if (running()) return "§cEs läuft schon ein Render-Vorgang. Mit /livemap fullrender stop beenden.";
        File dir = regionDir(w);
        if (dir == null) return "§cRegion-Ordner von '" + w.getName() + "' nicht gefunden.";
        File[] files = dir.listFiles((d, n) -> n.matches("r\\.-?\\d+\\.-?\\d+\\.mca"));
        if (files == null || files.length == 0) return "§cKeine Region-Dateien gefunden.";

        Location sp = w.getSpawnLocation();
        int scx = sp.getBlockX() >> 4, scz = sp.getBlockZ() >> 4;
        List<Reg> regs = new ArrayList<>();
        for (File f : files) {
            String[] p = f.getName().split("\\.");
            int rx = Integer.parseInt(p[1]), rz = Integer.parseInt(p[2]);
            regs.add(new Reg(f, rx, rz, Math.hypot(rx * 32 + 16 - scx, rz * 32 + 16 - scz)));
        }
        regs.sort(Comparator.comparingDouble(Reg::dist));   // von der Mitte nach aussen

        long[] q = new long[1024 * regs.size()];
        int n = 0;
        for (Reg r : regs) {
            try (RandomAccessFile raf = new RandomAccessFile(r.file(), "r")) {
                if (raf.length() < 4096) continue;
                byte[] h = new byte[4096];
                raf.readFully(h);
                for (int i = 0; i < 1024; i++) {
                    if ((h[i * 4] | h[i * 4 + 1] | h[i * 4 + 2]) == 0) continue;   // Chunk nicht vorhanden
                    int cx = r.rx() * 32 + (i & 31), cz = r.rz() * 32 + (i >> 5);
                    if (!force && store.has(w.getName(), cx, cz)) continue;
                    q[n++] = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Konnte " + r.file().getName() + " nicht lesen: " + e.getMessage());
            }
        }
        if (n == 0) return "§eNichts zu rendern, alles ist schon gespeichert. Mit 'force' erneut rendern.";

        queue = q; total = n; head = 0; done = 0; inFlight = 0; ticks = 0;
        world = w; sender = who; startMs = System.currentTimeMillis();
        int perTick = Math.max(1, plugin.getConfig().getInt("fullrender-chunks-per-tick", 3));
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(perTick), 1L, 1L);
        return "§aRender gestartet: " + n + " Chunks in '" + w.getName() + "'.";
    }

    private void tick(int perTick) {
        World w = world;
        int started = 0;
        while (head < total && inFlight < 8 && started < perTick) {
            long v = queue[head++];
            int cx = (int) (v >> 32), cz = (int) v;
            inFlight++;
            started++;
            w.getChunkAtAsync(cx, cz, false).whenComplete((chunk, err) -> {
                Runnable r = () -> {
                    try {
                        if (chunk != null && err == null) plugin.saveChunk(w, chunk);
                    } catch (Exception ignored) {
                    } finally {
                        inFlight--;
                        done++;
                    }
                };
                if (Bukkit.isPrimaryThread()) {
                    r.run();
                } else {
                    try {
                        Bukkit.getScheduler().runTask(plugin, r);
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        if (head >= total && inFlight <= 0) {
            msg("§aRender fertig: " + done + " Chunks in " + (System.currentTimeMillis() - startMs) / 1000 + " s.");
            task.cancel();
            task = null;
            return;
        }
        if (++ticks % 100 == 0) msg("§7Render: " + status());
    }

    private void msg(String m) {
        plugin.getLogger().info(m.replaceAll("§.", ""));
        if (sender != null) sender.sendMessage(m);
    }

    public String status() {
        if (!running()) return "Kein Render-Vorgang aktiv.";
        long sec = (System.currentTimeMillis() - startMs) / 1000;
        long eta = done > 0 ? sec * (total - done) / done : -1;
        return done + "/" + total + " (" + (done * 100 / total) + " %)" + (eta >= 0 ? ", noch ca. " + eta / 60 + " min" : "");
    }

    public String stop() {
        if (!running()) return "Kein Render-Vorgang aktiv.";
        task.cancel();
        task = null;
        return "§eRender gestoppt bei " + done + "/" + total + ". Mit /livemap fullrender startest du später genau dort weiter.";
    }
}
