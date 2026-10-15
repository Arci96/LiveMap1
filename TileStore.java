package de.livemap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Speichert die gerenderten Karten-Daten dauerhaft (pro Region = 32x32 Chunks eine .gz-Datei).
 * Pro Chunk 16x16 Pixel * 3 Bytes (RGB). RGB 0,0,0 bedeutet "keine Daten".
 */
public class TileStore {

    private static final int CH = 768;     // Bytes pro Chunk
    private static final int N = 1024;     // Chunks pro Region

    private static class Region {
        final String world;
        final int rx, rz;
        final byte[] data = new byte[N * CH];
        BitSet present = new BitSet(N);
        boolean dirty;

        Region(String world, int rx, int rz) {
            this.world = world;
            this.rx = rx;
            this.rz = rz;
        }
    }

    private final File dir;
    private final int maxRegions;
    private final Logger log;
    private final LinkedHashMap<String, Region> cache = new LinkedHashMap<>(16, 0.75f, true);

    public TileStore(File dir, int maxRegions, Logger log) {
        this.dir = dir;
        this.maxRegions = Math.max(4, maxRegions);
        this.log = log;
        dir.mkdirs();
    }

    private File file(String world, int rx, int rz) {
        return new File(new File(dir, world.replaceAll("[^A-Za-z0-9_.-]", "_")), "r." + rx + "." + rz + ".gz");
    }

    private Region region(String world, int rx, int rz, boolean create) {
        String k = world + "|" + rx + "|" + rz;
        Region r = cache.get(k);
        if (r != null) return r;
        File f = file(world, rx, rz);
        if (f.exists()) r = load(f, world, rx, rz);
        if (r == null && create) r = new Region(world, rx, rz);
        if (r != null) {
            cache.put(k, r);
            evict();
        }
        return r;
    }

    private Region load(File f, String world, int rx, int rz) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(f))))) {
            Region r = new Region(world, rx, rz);
            byte[] bits = new byte[128];
            in.readFully(bits);
            r.present = BitSet.valueOf(bits);
            in.readFully(r.data);
            return r;
        } catch (IOException e) {
            log.warning("Kachel-Datei beschaedigt, wird neu angelegt: " + f.getName());
            return null;
        }
    }

    private void write(Region r) {
        File f = file(r.world, r.rx, r.rz);
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (GZIPOutputStream out = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.write(Arrays.copyOf(r.present.toByteArray(), 128));
            out.write(r.data);
        } catch (IOException e) {
            log.warning("Kachel konnte nicht gespeichert werden: " + e.getMessage());
            return;
        }
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            r.dirty = false;
        } catch (IOException e) {
            log.warning("Kachel konnte nicht ersetzt werden: " + e.getMessage());
        }
    }

    private void evict() {
        while (cache.size() > maxRegions) {
            Iterator<Map.Entry<String, Region>> it = cache.entrySet().iterator();
            Region r = it.next().getValue();
            if (r.dirty) write(r);
            it.remove();
        }
    }

    public synchronized void flushAll() {
        for (Region r : cache.values()) if (r.dirty) write(r);
    }

    public synchronized boolean put(String world, int cx, int cz, int[] px) {
        boolean any = false;
        for (int v : px) if (v >= 0) { any = true; break; }
        if (!any) return false;
        Region r = region(world, cx >> 5, cz >> 5, true);
        int idx = ((cz & 31) << 5) | (cx & 31);
        int off = idx * CH;
        for (int i = 0; i < 256; i++) {
            int v = Math.max(px[i], 0);
            r.data[off + i * 3] = (byte) (v >> 16);
            r.data[off + i * 3 + 1] = (byte) (v >> 8);
            r.data[off + i * 3 + 2] = (byte) v;
        }
        r.present.set(idx);
        r.dirty = true;
        return true;
    }

    public synchronized boolean has(String world, int cx, int cz) {
        Region r = region(world, cx >> 5, cz >> 5, false);
        return r != null && r.present.get(((cz & 31) << 5) | (cx & 31));
    }

    public synchronized int[] getChunk(String world, int cx, int cz) {
        Region r = region(world, cx >> 5, cz >> 5, false);
        if (r == null) return null;
        int idx = ((cz & 31) << 5) | (cx & 31);
        if (!r.present.get(idx)) return null;
        int[] out = new int[256];
        int o = idx * CH;
        for (int i = 0; i < 256; i++) {
            int rgb = ((r.data[o + i * 3] & 255) << 16) | ((r.data[o + i * 3 + 1] & 255) << 8) | (r.data[o + i * 3 + 2] & 255);
            out[i] = rgb == 0 ? -1 : rgb;
        }
        return out;
    }

    /** Verkleinerte Ansicht einer ganzen Region als RGBA-Bytes ((512/f)^2 Pixel), null = leer. */
    public synchronized byte[] overview(String world, int rx, int rz, int f) {
        Region r = region(world, rx, rz, false);
        if (r == null || r.present.isEmpty()) return null;
        int n = 512 / f;
        byte[] out = new byte[n * n * 4];
        boolean any = false;
        for (int oy = 0; oy < n; oy++) {
            for (int ox = 0; ox < n; ox++) {
                int sr = 0, sg = 0, sb = 0, cnt = 0;
                for (int dy = 0; dy < f; dy++) {
                    int sy = oy * f + dy;
                    for (int dx = 0; dx < f; dx++) {
                        int sx = ox * f + dx;
                        int idx = ((sy >> 4) << 5) | (sx >> 4);
                        if (!r.present.get(idx)) continue;
                        int o = idx * CH + (((sy & 15) << 4) | (sx & 15)) * 3;
                        int rr = r.data[o] & 255, gg = r.data[o + 1] & 255, bb = r.data[o + 2] & 255;
                        if ((rr | gg | bb) == 0) continue;
                        sr += rr; sg += gg; sb += bb; cnt++;
                    }
                }
                if (cnt > 0) {
                    int p = (oy * n + ox) * 4;
                    out[p] = (byte) (sr / cnt);
                    out[p + 1] = (byte) (sg / cnt);
                    out[p + 2] = (byte) (sb / cnt);
                    out[p + 3] = (byte) 255;
                    any = true;
                }
            }
        }
        return any ? out : null;
    }
}
