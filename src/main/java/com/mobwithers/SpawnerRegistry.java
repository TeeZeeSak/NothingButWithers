package com.mobwithers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.block.Block;
import org.bukkit.entity.Creature;
import org.bukkit.entity.EntityType;
import org.bukkit.spawner.BaseSpawner;

/**
 * Remembers which mob a block spawner was configured to produce.
 *
 * <p>Vanilla {@code TRIAL_SPAWNER} / {@code OMINOUS_ITEM_SPAWNER} spawns route the spawned
 * entity type through the trial spawner's configuration rather than the event, so the
 * type has to be read from the block. Reading it before the spawn keeps the source mob's
 * loot table and identity available to the conversion.
 *
 * <p>Entries only need to survive the microseconds between reading the block and the spawn
 * event firing, but the map is keyed by block position so a delayed or re-entrant event
 * still finds the right entry. Entries expire on a tick budget.
 */
public final class SpawnerRegistry {

    /** How long a recorded spawner lookup stays valid, in ticks. */
    private static final int EXPIRY_TICKS = 2;

    private record Entry(EntityType type, int tick) {
    }

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    /** Records the mob type a spawner block is configured to emit. */
    public void remember(Block block, EntityType type) {
        if (block == null || type == null || type == EntityType.WITHER) {
            return;
        }
        entries.put(Chunkless.of(block), new Entry(type, org.bukkit.Bukkit.getCurrentTick()));
        if (entries.size() > 4096) {
            purge();
        }
    }

    /** Records whatever a spawner block is configured to emit, if anything. */
    public void remember(Block block) {
        if (block == null || !(block.getState() instanceof BaseSpawner spawner)) {
            return;
        }
        EntityType type = spawner.getSpawnedType();
        if (type != null && !isWitherLike(type)) {
            remember(block, type);
        }
    }

    /**
     * Recalls the type a spawner was about to emit.
     *
     * @return the remembered type, or {@code null} when nothing was recorded for the block.
     */
    public EntityType recall(Block block) {
        if (block == null) {
            return null;
        }
        Entry entry = entries.get(Chunkless.of(block));
        if (entry == null) {
            return null;
        }
        if (org.bukkit.Bukkit.getCurrentTick() - entry.tick() > EXPIRY_TICKS) {
            entries.remove(Chunkless.of(block), entry);
            return null;
        }
        return entry.type();
    }

    /**
     * Reads the mob type straight off a spawner block.
     *
     * <p>Used when the event fires without a preceding intercept, so converted spawner mobs
     * still keep the correct loot table.
     */
    public static EntityType typeOf(Block block) {
        if (block == null || !(block.getState() instanceof BaseSpawner spawner)) {
            return null;
        }
        EntityType type = spawner.getSpawnedType();
        return type == null || isWitherLike(type) ? null : type;
    }

    /** Drops expired entries. */
    public void purge() {
        int now = org.bukkit.Bukkit.getCurrentTick();
        entries.entrySet().removeIf(e -> now - e.getValue().tick() > EXPIRY_TICKS);
    }

    /** Drops every entry. */
    public void clear() {
        entries.clear();
    }

    /** True for entities that must never be treated as a loot source. */
    public static boolean isWitherLike(EntityType type) {
        return type == EntityType.WITHER;
    }

    /** True when the entity can be used as a horde marker without breaking a village. */
    public static boolean isSafeMarkerType(EntityType type) {
        if (type == null) {
            return false;
        }
        Class<? extends org.bukkit.entity.Entity> clazz = type.getEntityClass();
        return clazz != null && Creature.class.isAssignableFrom(clazz);
    }

    /** Position of a block as a single long, matching vanilla's chunk-relative encoding. */
    private static final class Chunkless {
        private Chunkless() {
        }

        static long of(Block block) {
            // Three 21-bit fields packed into a long: enough for the full +/-30M block range.
            return pack(block.getX(), block.getY(), block.getZ());
        }

        static long pack(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 42) | ((long) (z & 0x3FFFFFF) << 21) | (y & 0x3FFFFFF);
        }
    }

    /** Stable key for a block coordinate, exposed for bulk spawner scans. */
    public static long keyOf(int x, int y, int z) {
        return Chunkless.pack(x, y, z);
    }
}
