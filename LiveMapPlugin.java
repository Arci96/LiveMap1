package de.livemap;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class LiveMapPlugin extends JavaPlugin implements Listener {

    public record BlockEvent(long id, long time, String type, String player,
                             String block, String world, int x, int y, int z) {}

    private static final int MAX_EVENTS = 300;
    private final ConcurrentLinkedDeque<BlockEvent> events = new ConcurrentLinkedDeque<>();
    private final AtomicLong counter = new AtomicLong();

    private TileStore store;
    private MapServer server;
    private FullRender fullRender;
    private ExecutorService renderPool;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getString("token", "").isBlank()) {
            getConfig().set("token", UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            saveConfig();
        }

        store = new TileStore(new File(getDataFolder(), "tiles"), getConfig().getInt("max-cached-regions", 32), getLogger());
        fullRender = new FullRender(this, store);
        renderPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LiveMap-Render");
            t.setDaemon(true);
            return t;
        });

        getServer().getPluginManager().registerEvents(this, this);

        int sec = getConfig().getInt("live-refresh-seconds", 10);
        if (sec > 0) {
            getServer().getScheduler().runTaskTimer(this, () -> refreshAroundPlayers(3, 300), 100L, sec * 20L);
        }
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> store.flushAll(), 1200L, 1200L);

        try {
            server = new MapServer(this, store,
                    getConfig().getString("bind-address", "0.0.0.0"),
                    getConfig().getInt("port", 8123),
                    getConfig().getString("token", ""));
            server.start();
            getLogger().info("Live-Karte laeuft: " + url());
        } catch (Exception e) {
            getLogger().severe("Webserver konnte nicht gestartet werden: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (server != null) server.stop();
        if (fullRender != null) fullRender.stop();
        if (store != null && renderPool != null) {
            refreshAroundPlayers(6, 300);   // letzten Stand der Umgebung sichern
        }
        if (renderPool != null) {
            renderPool.shutdown();
            try {
                renderPool.awaitTermination(15, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }
        if (store != null) store.flushAll();
    }

    private String url() {
        String ip = Bukkit.getIp();
        if (ip == null || ip.isBlank()) ip = "localhost";
        return "http://" + ip + ":" + getConfig().getInt("port", 8123)
                + "/?token=" + getConfig().getString("token", "");
    }

    // ---------- Kachel-Speicher ----------

    /** Chunk jetzt (im Hauptthread) fotografieren und im Hintergrund rendern + speichern. */
    public void saveChunk(World w, Chunk c) {
        ChunkSnapshot snap = c.getChunkSnapshot(true, false, false);
        String name = w.getName();
        boolean nether = w.getEnvironment() == World.Environment.NETHER;
        int minY = w.getMinHeight(), maxY = w.getMaxHeight();
        int cx = c.getX(), cz = c.getZ();
        try {
            renderPool.execute(() -> {
                try {
                    store.put(name, cx, cz, ChunkRenderer.render(snap, nether, minY, maxY));
                } catch (Exception ex) {
                    getLogger().warning("Render-Fehler bei Chunk " + cx + "/" + cz + ": " + ex);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void refreshAroundPlayers(int radius, int budget) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            int pcx = p.getLocation().getBlockX() >> 4, pcz = p.getLocation().getBlockZ() >> 4;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (budget <= 0) return;
                    if (!w.isChunkLoaded(pcx + dx, pcz + dz)) continue;
                    budget--;
                    saveChunk(w, w.getChunkAt(pcx + dx, pcz + dz, false));
                }
            }
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        saveChunk(e.getWorld(), e.getChunk());   // letzter Stand wird gespeichert, bevor der Chunk verschwindet
    }

    // ---------- Befehle ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§aLiveMap: §f" + url());
            sender.sendMessage("§7Ganze Welt rendern: /livemap fullrender [welt] [force]  |  /livemap status");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "fullrender" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("stop")) {
                    sender.sendMessage(fullRender.stop());
                    return true;
                }
                String wn = args.length > 1 ? args[1]
                        : (sender instanceof Player p ? p.getWorld().getName() : Bukkit.getWorlds().get(0).getName());
                World w = Bukkit.getWorld(wn);
                if (w == null) {
                    sender.sendMessage("§cWelt '" + wn + "' nicht gefunden.");
                    return true;
                }
                boolean force = args.length > 2 && args[2].equalsIgnoreCase("force");
                sender.sendMessage(fullRender.start(w, force, sender));
            }
            case "status" -> sender.sendMessage("§7" + fullRender.status());
            default -> sender.sendMessage("§c/livemap | /livemap fullrender [welt] [force|stop] | /livemap status");
        }
        return true;
    }

    // ---------- Block-Ereignisse ----------

    private void log(String type, String player, String block, Location l) {
        if (l == null || l.getWorld() == null) return;
        events.addLast(new BlockEvent(counter.incrementAndGet(), System.currentTimeMillis(), type, player,
                block, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ()));
        while (events.size() > MAX_EVENTS) events.pollFirst();
    }

    public List<BlockEvent> eventsSince(long since) {
        List<BlockEvent> out = new ArrayList<>();
        for (BlockEvent e : events) if (e.id() > since) out.add(e);
        return out;
    }

    public long lastEventId() {
        return counter.get();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        log("place", e.getPlayer().getName(), b.getType().name().toLowerCase(Locale.ROOT), b.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        log("break", e.getPlayer().getName(), b.getType().name().toLowerCase(Locale.ROOT), b.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        log("explode", e.getEntityType().name().toLowerCase(Locale.ROOT),
                e.blockList().size() + " Blöcke", e.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        log("explode", "block", e.blockList().size() + " Blöcke", e.getBlock().getLocation());
    }
}
