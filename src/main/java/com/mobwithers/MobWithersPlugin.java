package com.mobwithers;

import java.util.Collection;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Wither;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MobWithers: every mob that spawns becomes a fully functional Wither that keeps the loot
 * table of the mob it replaced.
 *
 * <p>The plugin is organised as a set of small, single-purpose collaborators that this class
 * wires together. Nothing holds a reference to the live {@code FileConfiguration}; every
 * component reads the immutable {@link Settings} snapshot, which is swapped wholesale on
 * reload so a reload can never leave the server in a half-configured state.
 *
 * <h2>Component map</h2>
 * <ul>
 *   <li>{@link ConversionService} - decides eligibility, builds the replacement Wither.</li>
 *   <li>{@link SpawnListener} - every spawn event that can produce a mob.</li>
 *   <li>{@link LootListener} / {@link LootService} - retained loot tables and XP.</li>
 *   <li>{@link BlockEventListener} / {@link SculkReplacement} - infested blocks and
 *       shrieker-driven Warden replacement.</li>
 *   <li>{@link WaterSpawnGuard} - the crash-prevention cap suppression.</li>
 *   <li>{@link AiLimiter} / {@link TpsGuard} / {@link TerrainGuard} - performance.</li>
 *   <li>{@link BossBarManager} - hides the stacked Wither boss bars.</li>
 * </ul>
 */
public final class MobWithersPlugin extends JavaPlugin {

    private volatile Settings settings;

    private EntityKeys keys;
    private WitherIndex witherIndex;
    private ConversionService conversion;
    private LootService loot;
    private SpawnerRegistry spawnerRegistry;
    private SculkReplacement sculk;
    private TerrainGuard terrain;
    private TpsGuard tpsGuard;
    private WaterSpawnGuard waterGuard;
    private BossBarManager bossBars;
    private AiLimiter aiLimiter;
    private Statistics stats;

    /** Wall-clock timestamp used to suppress duplicate chunk scan work. */
    private volatile boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.settings = Settings.load(getConfig());

        this.keys = new EntityKeys(this);
        this.stats = new Statistics();
        this.tpsGuard = new TpsGuard();
        this.witherIndex = new WitherIndex(this, keys);
        this.spawnerRegistry = new SpawnerRegistry();
        this.conversion = new ConversionService(this, keys);
        this.loot = new LootService(this, keys);
        this.sculk = new SculkReplacement(this);
        this.terrain = new TerrainGuard(this);
        this.waterGuard = new WaterSpawnGuard(this);
        this.bossBars = new BossBarManager(this);
        this.aiLimiter = new AiLimiter(this);

        // The water guard must run before anything can spawn: if the first natural spawn
        // happens with a vanilla water cap in place, the runaway loop starts immediately.
        waterGuard.start();

        getServer().getPluginManager().registerEvents(new SpawnListener(this), this);
        getServer().getPluginManager().registerEvents(new LootListener(this), this);
        getServer().getPluginManager().registerEvents(new BlockEventListener(this), this);
        getServer().getPluginManager().registerEvents(new WorldListener(this), this);

        witherIndex.rebuild();
        conversion.rebuildCapacityCounters();
        bossBars.start();
        aiLimiter.start();

        // Sweep worlds that were already loaded before this plugin enabled. Deferred by one
        // tick so onEnable returns quickly and the server can finish starting up.
        if (settings.scanLoadedChunks) {
            Bukkit.getScheduler().runTask(this, this::scanAllLoadedChunks);
        }

        getLogger().info("MobWithers enabled: %s, %d types excluded."
                .formatted(tpsGuard.describe(), settings.excludedTypes.size()));
        if (!waterGuard.capsAreZeroed()) {
            getLogger().warning("Water spawn caps are not zeroed; the run-away water spawn "
                    + "loop is possible. Check for plugins or datapacks that override them.");
        }
    }

    @Override
    public void onDisable() {
        if (waterGuard != null) {
            waterGuard.stop();
        }
        if (bossBars != null) {
            bossBars.stop();
        }
        if (aiLimiter != null) {
            aiLimiter.stop();
        }
        if (spawnerRegistry != null) {
            spawnerRegistry.clear();
        }
        if (sculk != null) {
            sculk.clear();
        }
        getLogger().info("MobWithers disabled.");
    }

    // ------------------------------------------------------------------
    // Reload
    // ------------------------------------------------------------------

    /** Re-reads {@code config.yml} and restarts the timed components. */
    public void reload() {
        reloadConfig();
        this.settings = Settings.load(getConfig());
        spawnerRegistry.purge();
        sculk.clear();
        bossBars.stop();
        aiLimiter.stop();
        waterGuard.stop();

        waterGuard.start();
        bossBars.start();
        aiLimiter.start();
        conversion.rebuildCapacityCounters();
    }

    // ------------------------------------------------------------------
    // Chunk scanning
    // ------------------------------------------------------------------

    /** Converts the mobs a chunk already contains, used for pre-existing worlds. */
    public int scanLoadedEntities(Collection<Entity> entities) {
        if (!settings.scanLoadedChunks) {
            return 0;
        }
        int converted = 0;
        for (Entity entity : entities) {
            if (!(entity instanceof Mob mob)) {
                continue;
            }
            if (conversion.replacementFor(mob, SpawnReason.NATURAL) == null) {
                continue;
            }
            if (conversion.convert(mob, org.bukkit.entity.EntityType.WITHER, SpawnReason.NATURAL,
                    false, null) != null) {
                converted++;
            }
        }
        return converted;
    }

    /** Walks every loaded chunk in every world and converts the mobs already there. */
    public int scanAllLoadedChunks() {
        int converted = 0;
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                converted += scanLoadedEntities(List.of(chunk.getEntities()));
            }
        }
        if (converted > 0) {
            getLogger().info("Converted " + converted + " pre-existing mobs during the startup scan.");
        }
        return converted;
    }

    /** Deletes every converted Wither in every world. */
    public int purge() {
        int removed = 0;
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Wither wither : List.copyOf(world.getEntitiesByClass(Wither.class))) {
                if (!witherIndex.isConverted(wither)) {
                    continue;
                }
                witherIndex.unregister(wither);
                wither.remove();
                removed++;
            }
        }
        witherIndex.rebuild();
        conversion.rebuildCapacityCounters();
        return removed;
    }

    /** Buffers a spawner lookup so the matching spawn event finds the right loot table. */
    public void rememberSpawnerLookup(org.bukkit.block.Block block) {
        spawnerRegistry.remember(block);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("mobwithers")) {
            return false;
        }
        if (!sender.hasPermission("mobwithers.admin")) {
            sender.sendMessage("You do not have permission to use that.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "status" -> {
                stats.refreshLiveCounts();
                sender.sendMessage("MobWithers " + getPluginMeta().getVersion());
                sender.sendMessage("  server: " + tpsGuard.describe());
                sender.sendMessage("  conversions: " + (conversion.isHalted() ? "HALTED" : "active"));
                sender.sendMessage("  " + stats.report());
                sender.sendMessage("  terrain: " + terrain.describe());
                sender.sendMessage("  water caps: " + (waterGuard.capsAreZeroed() ? "zeroed" : "OVERRIDDEN"));
                sender.sendMessage("  boss bars: " + bossBars.describe());
            }
            case "reload" -> {
                reload();
                sender.sendMessage("MobWithers reloaded.");
            }
            case "purge" -> {
                int removed = purge();
                stats.reset();
                sender.sendMessage("Removed " + removed + " converted Withers.");
            }
            case "stats" -> {
                stats.refreshLiveCounts();
                sender.sendMessage(stats.report());
            }
            case "halt" -> {
                conversion.setHalted(true);
                sender.sendMessage("Conversions halted.");
            }
            case "resume" -> {
                conversion.setHalted(false);
                sender.sendMessage("Conversions resumed.");
            }
            case "scan" -> {
                int converted = scanAllLoadedChunks();
                sender.sendMessage("Converted " + converted + " pre-existing mobs.");
            }
            case "debug" -> {
                setDebug(!debug);
                sender.sendMessage("Debug logging " + (debug ? "enabled" : "disabled") + ".");
            }
            case "here" -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage("Run that in game.");
                    return true;
                }
                List<Wither> nearby = player.getLocation().getWorld() == null ? List.of()
                        : List.copyOf(player.getLocation().getWorld()
                                .getNearbyEntitiesByType(Wither.class, player.getLocation(), 32.0D));
                sender.sendMessage("Converted Withers within 32 blocks: " + nearby.size());
                for (Wither wither : nearby) {
                    sender.sendMessage("  " + conversion.describe(wither));
                }
            }
            default -> sender.sendMessage(
                    "Usage: /mobwithers <status|reload|purge|stats|halt|resume|scan|debug|here>");
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Accessors used by the collaborators
    // ------------------------------------------------------------------

    /** Current immutable settings snapshot. */
    public Settings settings() {
        return settings;
    }

    /** Persisted-data keys. */
    public EntityKeys keys() {
        return keys;
    }

    /** Boss bar suppression and aggregation. */
    public BossBarManager bossBars() {
        return bossBars;
    }

    /** Registry of converted Withers. */
    public WitherIndex witherIndex() {
        return witherIndex;
    }

    /** Spawn interception and replacement. */
    public ConversionService conversion() {
        return conversion;
    }

    /** Loot table rolling. */
    public LootService loot() {
        return loot;
    }

    /** Spawner block bookkeeping. */
    public SpawnerRegistry spawnerRegistry() {
        return spawnerRegistry;
    }

    /** Sculk shrieker replacement. */
    public SculkReplacement sculk() {
        return sculk;
    }

    /** Explosion clamping. */
    public TerrainGuard terrain() {
        return terrain;
    }

    /** Server load gate. */
    public TpsGuard tpsGuard() {
        return tpsGuard;
    }

    /** Water spawn cap enforcement. */
    public WaterSpawnGuard waterGuard() {
        return waterGuard;
    }

    /** Counters for the admin command. */
    public Statistics stats() {
        return stats;
    }

    /** True when verbose logging is on. */
    public boolean debug() {
        return debug;
    }

    /** Writes a diagnostic line only while debug mode is on. */
    public void debugTrace(String message) {
        if (debug) {
            getLogger().info("[trace] " + message);
        }
    }

    /**
     * Turns verbose logging on or off.
     *
     * <p>The plugin's diagnostic messages are written at {@code FINE}, which the server
     * console hides by default. Raising the logger level here is what makes {@code /mobwithers
     * debug} actually show anything, so the two always move together.
     */
    public void setDebug(boolean enabled) {
        this.debug = enabled;
        getLogger().setLevel(enabled ? java.util.logging.Level.ALL : java.util.logging.Level.INFO);
    }

    /** Reports a failed conversion, at a level that survives the default console filter. */
    public void logConversionFailure(org.bukkit.entity.EntityType sourceType, Throwable failure) {
        if (debug) {
            getLogger().log(java.util.logging.Level.WARNING, "Could not convert " + sourceType, failure);
        } else {
            getLogger().fine("Could not convert " + sourceType + ": " + failure);
        }
    }
}
