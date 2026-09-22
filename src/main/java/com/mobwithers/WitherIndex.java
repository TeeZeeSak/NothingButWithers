package com.mobwithers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Wither;
import org.bukkit.persistence.PersistentDataContainer;

/**
 * Tracks which Withers this plugin created.
 *
 * <p>The authoritative marker is the persisted data on the entity itself, which survives
 * restarts; this class only keeps an in-memory mirror for fast cap checks and the admin
 * command.
 *
 * <h2>Why the cap counts loaded Withers only</h2>
 *
 * <p>An earlier version incremented a per-world counter on conversion and decremented it on
 * death, and treated that counter as the cap. That leaks: any Wither that leaves the loaded
 * area is never unloaded from the mirror, so its count survives for the life of the server.
 * Once {@code max-withers-per-world} such Withers accumulated, the cap was permanently
 * saturated and <em>every</em> subsequent spawn in that world was refused, including in chunks
 * thousands of blocks away from any Wither. A player who walked away from spawn would find no
 * conversions and no boss bars anywhere.
 *
 * <p>The cap is a performance limit: its purpose is to bound the number of Withers the server
 * has to tick, and only loaded Withers tick. Counting loaded entities therefore measures the
 * thing the cap exists to protect, and it cannot drift. {@link #count(World)} is served from
 * that live count.
 *
 * <p>Withers that are registered but not currently loaded are retained separately in
 * {@link #unloaded} so {@link #total()} and the purge path can still reach them, but they do
 * not consume cap.
 */
public final class WitherIndex {

    private final MobWithersPlugin plugin;
    private final EntityKeys keys;

    /** UUID -> world the Wither lives in, for every Wither this plugin has created. */
    private final Map<UUID, World> tracked = new ConcurrentHashMap<>();

    /** Tracked Withers whose chunk is not loaded, so they cannot be counted as ticking. */
    private final Map<UUID, World> unloaded = new ConcurrentHashMap<>();

    /** Live (loaded) Wither counts per world, recomputed from the world by {@link #refresh}. */
    private final Map<UUID, Integer> loadedCounts = new ConcurrentHashMap<>();

    public WitherIndex(MobWithersPlugin plugin, EntityKeys keys) {
        this.plugin = plugin;
        this.keys = keys;
    }

    /** Remembers a Wither this plugin spawned. */
    public void register(Wither wither) {
        World world = wither.getWorld();
        tracked.put(wither.getUniqueId(), world);
        if (wither.isValid() && !wither.isDead()) {
            unloaded.remove(wither.getUniqueId());
            loadedCounts.compute(world.getUID(), (id, count) -> count == null ? 1 : count + 1);
        } else {
            unloaded.put(wither.getUniqueId(), world);
        }
    }

    /** Forgets a Wither, typically because it died. */
    public void unregister(Entity entity) {
        World world = tracked.remove(entity.getUniqueId());
        if (world == null) {
            return;
        }
        if (unloaded.remove(entity.getUniqueId()) == null) {
            decrementLoaded(world);
        }
    }

    /**
     * True when this entity is a Wither created by the plugin.
     *
     * <p>Reads the marker straight off the entity rather than the mirror, so it is correct for
     * Withers that were loaded by a server restart or by chunk loading.
     */
    public boolean isConverted(LivingEntity entity) {
        if (!(entity instanceof Wither) && entity.getType() != org.bukkit.entity.EntityType.WITHER) {
            return false;
        }
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        Byte flag = pdc.get(keys.converted, EntityKeys.BYTE);
        return flag != null && flag == (byte) 1;
    }

    /**
     * Number of <em>loaded</em> tracked Withers in a world.
     *
     * <p>This is the cap basis. Kept fresh by {@link #refresh()}; {@link #noteLoaded} and
     * {@link #noteUnloaded} keep it exact between refreshes.
     */
    public int count(World world) {
        Integer count = loadedCounts.get(world.getUID());
        return count == null ? 0 : Math.max(0, count);
    }

    /** Total tracked Withers across every world, loaded or not. */
    public int total() {
        return tracked.size();
    }

    /** Snapshot of the tracked UUIDs, used by the purge subcommand. */
    public Iterable<UUID> trackedIds() {
        return tracked.keySet();
    }

    /** Records that a tracked Wither entered the loaded area. */
    public void noteLoaded(Wither wither) {
        if (tracked.putIfAbsent(wither.getUniqueId(), wither.getWorld()) != null) {
            return;
        }
        unloaded.remove(wither.getUniqueId());
        loadedCounts.compute(wither.getWorld().getUID(), (id, count) -> count == null ? 1 : count + 1);
    }

    /** Records that a tracked Wither left the loaded area. */
    public void noteUnloaded(Entity entity) {
        World world = tracked.get(entity.getUniqueId());
        if (world == null) {
            return;
        }
        if (unloaded.putIfAbsent(entity.getUniqueId(), world) == null) {
            decrementLoaded(world);
        }
    }

    /** Recomputes the loaded counts from the worlds; called on enable and after a purge. */
    public void rebuild() {
        tracked.clear();
        unloaded.clear();
        loadedCounts.clear();
        refresh();
    }

    /** Re-derives the loaded counts and tracked set from currently loaded entities. */
    public void refresh() {
        loadedCounts.clear();
        unloaded.clear();
        for (World world : Bukkit.getWorlds()) {
            int count = 0;
            for (Wither wither : world.getEntitiesByClass(Wither.class)) {
                if (!isConverted(wither)) {
                    continue;
                }
                tracked.put(wither.getUniqueId(), world);
                count++;
            }
            loadedCounts.put(world.getUID(), count);
        }
    }

    /** Drops every tracked entry. */
    public void clear() {
        tracked.clear();
        unloaded.clear();
        loadedCounts.clear();
    }

    /** Removes a specific Wither from the mirror without affecting the entity. */
    public void forget(UUID id) {
        World world = tracked.remove(id);
        if (world == null) {
            return;
        }
        if (unloaded.remove(id) == null) {
            decrementLoaded(world);
        }
    }

    private void decrementLoaded(World world) {
        loadedCounts.computeIfPresent(world.getUID(), (id, count) -> count <= 1 ? 0 : count - 1);
    }
}
