package com.mobwithers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Wither;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * The heart of the plugin: decides when a mob may be replaced, and builds the Wither that
 * takes its place.
 *
 * <p>Responsibilities are intentionally narrow. This class owns <em>eligibility</em> and
 * <em>construction</em>; loot rolling lives in {@link LootService}, spawner bookkeeping in
 * {@link SpawnerRegistry}, and terrain clamping in {@link TerrainGuard}.
 */
public final class ConversionService {

    private final MobWithersPlugin plugin;
    private final EntityKeys keys;

    /** Chunk-key -> live plugin-spawned Withers in that chunk, for the per-chunk cap. */
    private final Map<Long, Integer> perChunk = new ConcurrentHashMap<>();

    /** Admin kill-switch, independent of the config flag. */
    private volatile boolean halted;

    public ConversionService(MobWithersPlugin plugin, EntityKeys keys) {
        this.plugin = plugin;
        this.keys = keys;
    }

    // ------------------------------------------------------------------
    // Eligibility
    // ------------------------------------------------------------------

    /**
     * Decides whether {@code entity} should be replaced.
     *
     * @return {@link EntityType#WITHER} when the entity should become a Wither, or
     *         {@code null} when it must be left alone.
     */
    public EntityType replacementFor(Mob mob, SpawnReason reason) {
        Settings s = plugin.settings();
        if (!s.enabled || halted) {
            return null;
        }
        // Guard against re-entrancy: the Wither this plugin spawns also fires spawn events.
        if (mob.getType() == EntityType.WITHER) {
            return null;
        }
        // The Ender Dragon must survive untouched or the game cannot be completed.
        if (mob.getType() == EntityType.ENDER_DRAGON) {
            return null;
        }
        if (s.excludedTypes.contains(mob.getType())) {
            return null;
        }
        if (!reasonAllowed(s, reason)) {
            return null;
        }
        if (s.skipNamedMobs && mob.customName() != null) {
            return null;
        }
        if (s.skipTamedMobs && mob instanceof Tameable tameable && tameable.isTamed()) {
            return null;
        }
        if (s.skipVehicles && (mob.isInsideVehicle() || !mob.getPassengers().isEmpty())) {
            return null;
        }
        return EntityType.WITHER;
    }

    /**
     * Maps a vanilla spawn reason onto the configurable conversion category.
     *
     * <p>Package-private rather than private so the mapping can be unit tested without a
     * running server, which is the only way to verify the exclusions on CI.
     */
    static boolean reasonAllowed(Settings s, SpawnReason reason) {
        if (reason == null) {
            return s.convertExternal;
        }
        return switch (reason) {
            // CHUNK_GEN is deliberately absent: it is never called any more because chunks
            // generate with their entities already present. Those mobs are caught by the
            // chunk scan rather than by a spawn event.
            case NATURAL, PATROL, RAID, VILLAGE_INVASION, NETHER_PORTAL, REINFORCEMENTS,
                 LIGHTNING -> s.convertNatural;
            case SPAWNER, TRIAL_SPAWNER, OMINOUS_ITEM_SPAWNER -> s.convertSpawners;
            case SILVERFISH_BLOCK, INFECTION, METAMORPHOSIS -> s.convertBlockEvents;
            case BUILD_WITHER, COMMAND, SPAWNER_EGG, EGG, DISPENSE_EGG, CUSTOM, DEFAULT, BUCKET,
                 SHEARED, CURED, BREEDING, MOUNT, TRAP, SLIME_SPLIT, DUPLICATION, EXPLOSION,
                 ENDER_PEARL, ENCHANTMENT -> s.convertExternal;
            // Jockey mounts, village defence golems and shoulder entities are deliberately
            // excluded: converting them detaches passengers and breaks their host logic.
            default -> false;
        };
    }

    /** True while conversions are paused through the admin command. */
    public boolean isHalted() {
        return halted;
    }

    /** Pauses or resumes conversions. */
    public void setHalted(boolean halted) {
        this.halted = halted;
    }

    // ------------------------------------------------------------------
    // Conversion
    // ------------------------------------------------------------------

    /**
     * Replaces {@code original} with a Wither.
     *
     * @param original         the mob to replace, already cancelled by the spawn event
     * @param replacementType  the entity type to spawn, always a Wither today
     * @param reason           spawn reason to record
     * @param spawnerBorn      whether a spawner block produced this mob
     * @param sourceTypeOverride the spawner's configured mob, or {@code null} to trust the entity
     * @return the spawned Wither, or {@code null} when a guard refused the spawn
     */
    public boolean schedule(Mob original, EntityType replacementType, SpawnReason reason,
                            boolean spawnerBorn, EntityType sourceTypeOverride) {
        // The source mob has already been discarded by the cancelled spawn event, so every
        // value the replacement needs is copied out here, while it can still be read.
        ConversionRequest request = ConversionRequest.capture(original, replacementType, reason,
                spawnerBorn, sourceTypeOverride);
        if (request == null) {
            return false;
        }
        // Deferring to the next tick is required for event-driven conversions. Creating the
        // entity inline appears to succeed and returns a Wither, but the server finishes
        // removing the cancelled original afterwards and drops the replacement with it, so the
        // conversion silently produces nothing.
        Bukkit.getScheduler().runTask(plugin, () -> convertNow(request, original));
        return true;
    }

    /**
     * Replaces a mob with a Wither, immediately.
     *
     * <p>Safe for mobs that already exist in a loaded chunk and were not the subject of a
     * cancelled spawn event; the startup scan and the admin command use this path.
     */
    public Wither convert(Mob original, EntityType replacementType, SpawnReason reason,
                          boolean spawnerBorn, EntityType sourceTypeOverride) {
        ConversionRequest request = ConversionRequest.capture(original, replacementType, reason,
                spawnerBorn, sourceTypeOverride);
        return request == null ? null : convertNow(request, original);
    }

    private Wither convertNow(ConversionRequest request, Mob original) {
        EntityType replacementType = request.replacementType();
        SpawnReason reason = request.reason();
        boolean spawnerBorn = request.spawnerBorn();
        EntityType sourceTypeOverride = request.sourceTypeOverride();
        Settings s = plugin.settings();
        if (!plugin.tpsGuard().allowConversion(s)) {
            plugin.stats().refused("tps");
            return null;
        }

        Location location = request.location();
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        if (plugin.witherIndex().count(world) >= s.maxWithersPerWorld) {
            plugin.stats().refused("world");
            return null;
        }
        if (!reserveCapacity(s, world, location)) {
            plugin.stats().refused("chunk");
            return null;
        }

        EntityType sourceType = request.sourceType();
        NamespacedKey lootKey = request.lootKey();
        String sourceName = request.customName();
        boolean sourcePersistent = request.persistent();
        float yaw = request.yaw();
        float pitch = request.pitch();

        // The chunk must be loaded before the replacement is created. A cancelled spawn can
        // leave the target chunk unloaded, and an entity created into an unloaded chunk is
        // discarded by the server before it ever ticks: the spawn call still returns a Wither,
        // but the entity is already invalid and nothing appears in the world.
        world.getChunkAt(location);

        Wither wither;
        try {
            removeQuietly(original);
            wither = world.spawn(location, Wither.class, SpawnReason.CUSTOM, true, spawned -> {
                // Vanilla Withers are not persistent, so it has to be re-applied here.
                spawned.setPersistent(sourcePersistent);
                spawned.setRemoveWhenFarAway(false);
                if (s.inheritCustomName && sourceName != null) {
                    spawned.setCustomName(sourceName);
                }
            });
        } catch (RuntimeException | LinkageError spawnFailed) {
            releaseCapacity(location);
            plugin.logConversionFailure(sourceType, spawnFailed);
            return null;
        }

        if (wither == null || !wither.isValid()) {
            releaseCapacity(location);
            plugin.debugTrace("replacement for " + sourceType + " did not survive creation");
            return null;
        }
        plugin.debugTrace("spawned " + wither.getType() + " valid=" + wither.isValid());

        // World#spawn preserves yaw/pitch from the location, but a fresh Wither may be given
        // the wrong facing when it mounts a chunk border; re-asserting is free and reliable.
        Location facing = wither.getLocation();
        facing.setYaw(yaw);
        facing.setPitch(pitch);

        try {
            applyIdentity(wither, sourceType, lootKey, reason, spawnerBorn, s);
            plugin.witherIndex().register(wither);
        } catch (RuntimeException identityFailure) {
            plugin.logConversionFailure(sourceType, identityFailure);
            return null;
        }
        plugin.stats().converted();
        plugin.bossBars().suppressSoon(wither);
        return wither;
    }

    /**
     * Removes the source mob without letting an already-discarded entity abort the conversion.
     *
     * <p>By the time most conversions run the mob has been dropped from the world as part of
     * the cancelled spawn event, and touching it can throw. Failing to remove it is harmless:
     * the goal is only that it does not linger alongside its replacement.
     */
    private void removeQuietly(Mob original) {
        try {
            if (original.isValid()) {
                original.remove();
            }
        } catch (RuntimeException alreadyGone) {
            // The entity was discarded by the event that triggered us; nothing left to do.
        }
    }

    /**
     * Summons a persistent "Horde Wither" for a sculk shrieker.
     *
     * <p>Unlike a converted mob this one is intentionally permanent: it never despawns and
     * never burrows away, matching the requirement to outlive the vanilla Warden's calm-down
     * behaviour. It deliberately bypasses the per-world and per-chunk caps because the
     * shrieker threshold is already the rate limiter.
     */
    public Wither summonHordeWither(Location location, Entity source) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        EntityType sourceType = source == null ? EntityType.WARDEN : source.getType();
        Wither wither;
        try {
            wither = world.spawn(location, Wither.class, SpawnReason.CUSTOM, true, spawned -> {
                spawned.setPersistent(true);
                spawned.setRemoveWhenFarAway(false);
                spawned.setCanTravelThroughPortals(true);
            });
        } catch (RuntimeException | LinkageError spawnFailed) {
            plugin.getLogger().fine("Could not summon a horde Wither: " + spawnFailed);
            return null;
        }
        if (wither == null) {
            return null;
        }
        applyIdentity(wither, sourceType, MobLootTables.keyFor(sourceType), SpawnReason.CUSTOM, false,
                plugin.settings());
        wither.getPersistentDataContainer().set(keys.sculkSummoned, EntityKeys.BYTE, (byte) 1);
        plugin.witherIndex().register(wither);
        return wither;
    }

    private void applyIdentity(Wither wither, EntityType sourceType, NamespacedKey lootKey,
                               SpawnReason reason, boolean spawnerBorn, Settings s) {
        PersistentDataContainer pdc = wither.getPersistentDataContainer();
        pdc.set(keys.converted, EntityKeys.BYTE, (byte) 1);
        if (sourceType != null) {
            pdc.set(keys.originalMob, PersistentDataType.STRING, sourceType.name());
        }
        if (lootKey != null) {
            pdc.set(keys.originalLootTable, PersistentDataType.STRING, lootKey.toString());
        }
        if (reason != null) {
            pdc.set(keys.spawnReason, PersistentDataType.STRING, reason.name());
        }
        pdc.set(keys.convertedAt, PersistentDataType.LONG, (long) Bukkit.getCurrentTick());
        if (spawnerBorn && s.inheritSpawnerEntityType) {
            pdc.set(keys.spawnerBorn, EntityKeys.BYTE, (byte) 1);
        }
        WitherTuning.tune(wither, s);
    }

    // ------------------------------------------------------------------
    // Capacity accounting
    // ------------------------------------------------------------------

    private boolean reserveCapacity(Settings s, World world, Location location) {
        if (plugin.witherIndex().count(world) >= s.maxWithersPerWorld) {
            return false;
        }
        long chunkKey = Chunk.getChunkKey(location);
        int inChunk = perChunk.getOrDefault(chunkKey, 0);
        if (inChunk >= s.maxWithersPerChunk) {
            return false;
        }
        perChunk.put(chunkKey, inChunk + 1);
        return true;
    }

    private void releaseCapacity(Location location) {
        long chunkKey = Chunk.getChunkKey(location);
        perChunk.computeIfPresent(chunkKey, (key, count) -> count <= 1 ? null : count - 1);
    }

    /** Drops all per-chunk counters. */
    public void resetCapacityCounters() {
        perChunk.clear();
    }

    /** Recomputes per-chunk counters from the live worlds. */
    public void rebuildCapacityCounters() {
        perChunk.clear();
        for (World world : Bukkit.getWorlds()) {
            for (Wither wither : world.getEntitiesByClass(Wither.class)) {
                if (plugin.witherIndex().isConverted(wither)) {
                    perChunk.merge(Chunk.getChunkKey(wither.getLocation()), 1, Integer::sum);
                }
            }
        }
    }

    /** Where a converted Wither came from, for the admin command. */
    public record Origin(String mobName, String lootTable, String reason, UUID uuid) {
        @Override
        public String toString() {
            return "%s (loot=%s, reason=%s)".formatted(mobName, lootTable, reason);
        }
    }

    /** Reads the persisted origin of a converted Wither. */
    public Origin describe(LivingEntity wither) {
        PersistentDataContainer pdc = wither.getPersistentDataContainer();
        String mob = pdc.getOrDefault(keys.originalMob, PersistentDataType.STRING, "unknown");
        String loot = pdc.getOrDefault(keys.originalLootTable, PersistentDataType.STRING, "none");
        String reason = pdc.getOrDefault(keys.spawnReason, PersistentDataType.STRING, "unknown");
        return new Origin(mob, loot, reason, wither.getUniqueId());
    }
}
