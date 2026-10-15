package de.livemap;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** Wandelt einen Chunk in 256 Farbpunkte (Draufsicht) um. */
public final class ChunkRenderer {

    private ChunkRenderer() {}

    public static int[] render(ChunkSnapshot s, World w) {
        return render(s, w.getEnvironment() == World.Environment.NETHER, w.getMinHeight(), w.getMaxHeight());
    }

    /** Liefert 256 RGB-Werte (Index = z*16+x), -1 = keine Daten. */
    public static int[] render(ChunkSnapshot s, boolean nether, int minY, int maxY) {
        int[] ys = new int[256];
        int[] base = new int[256];
        int[] depth = new int[256];

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                int y;
                if (nether) {
                    y = Math.min(maxY - 1, 127);
                    while (y >= minY && !s.getBlockType(x, y, z).isAir()) y--;
                    while (y >= minY && s.getBlockType(x, y, z).isAir()) y--;
                } else {
                    y = Math.min(s.getHighestBlockYAt(x, z) + 1, maxY - 1);
                    while (y >= minY && s.getBlockType(x, y, z).isAir()) y--;
                }
                if (y < minY) {
                    ys[i] = minY;
                    base[i] = -1;
                    continue;
                }
                ys[i] = y;
                BlockData data = s.getBlockData(x, y, z);
                if (data.getMaterial() == Material.WATER) {
                    int dd = 0;
                    while (dd < 12 && y - dd >= minY && s.getBlockType(x, y - dd, z) == Material.WATER) dd++;
                    depth[i] = dd;
                }
                base[i] = data.getMapColor().asRGB();
            }
        }

        int[] out = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                if (base[i] < 0) {
                    out[i] = -1;
                    continue;
                }
                int shade;
                if (depth[i] > 0) {
                    shade = Math.max(100, 255 - depth[i] * 14);
                } else {
                    int north = z > 0 ? ys[i - 16] : ys[i];
                    shade = ys[i] > north ? 255 : (ys[i] == north ? 220 : 180);
                }
                int c = base[i];
                int r = ((c >> 16) & 255) * shade / 255;
                int g = ((c >> 8) & 255) * shade / 255;
                int b = (c & 255) * shade / 255;
                int rgb = (r << 16) | (g << 8) | b;
                out[i] = rgb == 0 ? 0x010101 : rgb;   // 0 ist im Speicher "keine Daten"
            }
        }
        return out;
    }
}
