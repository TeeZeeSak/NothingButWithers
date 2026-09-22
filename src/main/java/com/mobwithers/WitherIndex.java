package com.mobwithers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Wither;
import org.bukkit.persistence.PersistentDataContainer;

/**
 * Tracks which Withers this plugin created.
 *
 * <p>A {@code Set<UUID>} lookup would require a full registry sweep on every death, so the
 * authoritative marker is the persisted data on the entity itself (which survives restarts)
 * and this class only keeps an in-memory mirror for fast aggregate counts and the admin
 * command. Entries are dropped when a Wither dies or unloads.
 */
public final class WitherIndex {

    private final MobWithersPlugin plugin;
    private final EntityKeys keys;

    /** UUID -> world the Wither lives in. */
    private final Map<UUID, World> tracked = new ConcurrentHashMap<>();

    /** Cached per-world counts so cap checks are O(1) rather than O(entities). */
    private final Map<UUID, AtomicInteger> perWorldCounts = new ConcurrentHashMap<>();

    public WitherIndex(MobWithersPlugin plugin, EntityKeys keys) {
        this.plugin = plugin;
        this.keys = keys;
    }

    /** Remembers a Wither this plugin spawned. */
    public void register(Wither wither) {
        World world = wither.getWorld();
        World previous = tracked.put(wither.getUniqueId(), world);
        if (previous == null) {
            perWorldCounts.computeIfAbsent(world.getUID(), id -> new AtomicInteger()).incrementAndGet();
        }
    }

    /** Forgets a Wither, typically because it died. */
    public void unregister(Entity entity) {
        World world = tracked.remove(entity.getUniqueId());
        if (world != null) {
            AtomicInteger counter = perWorldCounts.get(world.getUID());
            if (counter != null) {
                counter.decrementAndGet();
            }
        }
    }

    /** True when this entity is a Wither created by the plugin. */
    public boolean isConverted(LivingEntity entity) {
        if (!(entity instanceof Wither) && entity.getType() != org.bukkit.entity.EntityType.WITHER) {
            return false;
        }
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        Byte flag = pdc.get(keys.converted, EntityKeys.BYTE);
        return flag != null && flag == (byte) 1;
    }

    /**
     * Number of tracked Withers in a world.
     *
     * <p>The counter is authoritative for {@code track()}-ed entities. A world whose counter
     * has drifted negative (for example after a crash) is clamped to zero.
     */
    public int count(World world) {
        AtomicInteger counter = perWorldCounts.get(world.getUID());
        return counter == null ? 0 : Math.max(0, counter.get());
    }

    /** Total tracked Withers across every world. */
    public int total() {
        int sum = 0;
        for (AtomicInteger counter : perWorldCounts.values()) {
            sum += Math.max(0, counter.get());
        }
        return sum;
    }

    /** Snapshot of the tracked UUIDs, used by the purge subcommand. */
    public Iterable<UUID> trackedIds() {
        return tracked.keySet();
    }

    /** Rebuilds the mirror from the loaded worlds; called on enable and after a purge. */
    public void rebuild() {
        tracked.clear();
        perWorldCounts.clear();
        for (World world : Bukkit.getWorlds()) {
            for (Wither wither : world.getEntitiesByClass(Wither.class)) {
                if (isConverted(wither)) {
                    tracked.put(wither.getUniqueId(), world);
                    perWorldCounts.computeIfAbsent(world.getUID(), id -> new AtomicInteger())
                            .incrementAndGet();
                }
            }
        }
    }

    /** Drops every tracked entry. */
    public void clear() {
        tracked.clear();
        perWorldCounts.clear();
    }

    /** Removes a specific Wither from the mirror without affecting the entity. */
    public void forget(UUID id) {
        World world = tracked.remove(id);
        if (world != null) {
            AtomicInteger counter = perWorldCounts.get(world.getUID());
            if (counter != null) {
                counter.decrementAndGet();
            }
        }
    }
}
