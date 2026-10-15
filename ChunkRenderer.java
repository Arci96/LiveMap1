package de.livemap;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** Wandelt einen Chunk in 256 Farbpunkte (Draufsicht) und 256 Hoehen um. */
public final class ChunkRenderer {

    private ChunkRenderer() {}

    public static int[] render(ChunkSnapshot s, World w) {
        return render(s, w.getEnvironment() == World.Environment.NETHER, w.getMinHeight(), w.getMaxHeight());
    }

    /** Liefert 256 RGB-Werte (Index = z*16+x), -1 = keine Daten. */
    public static int[] render(ChunkSnapshot s, boolean nether, int minY, int maxY) {
        return java.util.Arrays.copyOf(renderFull(s, nether, minY, maxY), 256);
    }

    /** Blocks, die man von oben "durchsieht": Luft, Gras, Blumen, Fackeln, Schienen ... (Wasser/Lava nicht). */
    private static boolean pass(Material m) {
        if (m.isAir()) return true;
        if (m == Material.WATER || m == Material.LAVA || m == Material.SNOW) return false;
        return !m.isSolid();
    }

    /** 768 Werte: 256 Farben, 256 Hoehen (Y des obersten sichtbaren Blocks), 256 Block-IDs (oben | darunter << 16). */
    public static int[] renderFull(ChunkSnapshot s, boolean nether, int minY, int maxY) {
        int[] ys = new int[256];
        int[] base = new int[256];
        int[] depth = new int[256];
        int[] ids = new int[256];

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                int y;
                if (nether) {
                    y = Math.min(maxY - 1, 127);
                    while (y >= minY && !pass(s.getBlockType(x, y, z))) y--;   // durch die Decke
                    while (y >= minY && pass(s.getBlockType(x, y, z))) y--;    // bis zum Boden
                } else {
                    y = Math.min(s.getHighestBlockYAt(x, z) + 1, maxY - 1);
                    while (y >= minY && pass(s.getBlockType(x, y, z))) y--;
                }
                if (y < minY) {
                    ys[i] = minY;
                    base[i] = -1;
                    continue;
                }
                ys[i] = y;
                BlockData data = s.getBlockData(x, y, z);
                Material m = data.getMaterial();
                if (m == Material.WATER) {
                    int dd = 0;
                    while (dd < 12 && y - dd >= minY && s.getBlockType(x, y - dd, z) == Material.WATER) dd++;
                    depth[i] = dd;
                }
                int tid = BlockTex.id(m);
                int uid = tid;
                if (m != Material.WATER && m != Material.LAVA && y - 1 >= minY) {
                    Material m2 = s.getBlockType(x, y - 1, z);
                    if (!pass(m2) && m2 != Material.WATER && m2 != Material.LAVA) uid = BlockTex.id(m2);
                }
                ids[i] = tid | (uid << 16);
                Integer c = BlockColors.get(m);
                base[i] = c != null ? c : data.getMapColor().asRGB();
            }
        }

        int[] out = new int[768];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                if (base[i] < 0) {
                    out[i] = -1;
                    continue;
                }
                int shade;
                if (depth[i] > 0) {
                    shade = Math.max(110, 255 - depth[i] * 13);
                } else {
                    int north = z > 0 ? ys[i - 16] : ys[i];
                    shade = ys[i] > north ? 255 : (ys[i] == north ? 238 : 205);
                }
                int c = base[i];
                int r = ((c >> 16) & 255) * shade / 255;
                int g = ((c >> 8) & 255) * shade / 255;
                int b = (c & 255) * shade / 255;
                int rgb = (r << 16) | (g << 8) | b;
                out[i] = rgb == 0 ? 0x010101 : rgb;   // 0 ist im Speicher "keine Daten"
            }
        }
        System.arraycopy(ys, 0, out, 256, 256);
        System.arraycopy(ids, 0, out, 512, 256);
        return out;
    }
}
