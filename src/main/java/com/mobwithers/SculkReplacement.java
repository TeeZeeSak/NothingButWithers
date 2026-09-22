package com.mobwithers;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.util.Vector;

/**
 * Replaces the Warden with a persistent "Horde Wither".
 *
 * <p>Vanilla summons a Warden after a sculk shrieker is triggered a fourth time. The Warden
 * then despawns or burrows away once the area goes quiet for sixty seconds. The replacement
 * is a Wither that is explicitly marked persistent, so it stays put indefinitely, matching
 * the requirement that it should not despawn or dig back underground.
 *
 * <p>Detection uses {@code BlockReceiveGameEvent} with the {@code SHRIEK} game event, which
 * fires once per activation. That is the only stable Bukkit hook: the shrieker's internal
 * counter is not exposed, so this class reproduces it.
 */
public final class SculkReplacement {

    /** Block position key -> number of shrieks observed. */
    private final Map<Long, Integer> shriekCounts = new ConcurrentHashMap<>();

    /** Cooldown so one player standing on a shrieker cannot summon repeatedly forever. */
    private final Map<Long, Integer> lastSummonTick = new ConcurrentHashMap<>();

    private final MobWithersPlugin plugin;

    /** Rebuild the counts after this many idle ticks so stale positions are reclaimed. */
    private static final int IDLE_RESET_TICKS = 20 * 60;

    private final Map<Long, Integer> lastTouch = new ConcurrentHashMap<>();

    public SculkReplacement(MobWithersPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Handles one shriek activation.
     *
     * @return the summoned Wither, or {@code null} when the threshold has not been reached.
     */
    public Wither onShriek(Location shriekerLocation, Entity activator) {
        Settings s = plugin.settings();
        if (!s.enabled) {
            return null;
        }

        long key = SpawnerRegistry.keyOf(shriekerLocation.getBlockX(), shriekerLocation.getBlockY(),
                shriekerLocation.getBlockZ());
        int now = Bukkit.getCurrentTick();
        lastTouch.put(key, now);

        int count = shriekCounts.merge(key, 1, Integer::sum);
        if (count < s.shriekerActivations) {
            return null;
        }

        // Reset the local counter so the next four shrieks summon again, matching vanilla,
        // but rate limit so a shrieker cannot be machine-gunned with dispensers.
        shriekCounts.put(key, 0);
        Integer last = lastSummonTick.get(key);
        if (last != null && now - last < 20 * 10) {
            return null;
        }
        lastSummonTick.put(key, now);

        Player witness = findNearbyPlayer(shriekerLocation, s.shriekerPlayerRadius);
        if (witness == null) {
            // Vanilla only summons when a player is present; without one there is nothing
            // to be angry at and the Wither would immediately wander off.
            return null;
        }

        Location spawnAt = safeSpawnLocation(shriekerLocation);
        Wither wither = plugin.conversion().summonHordeWither(spawnAt, activator);
        if (wither == null) {
            return null;
        }

        if (witness != null) {
            wither.setTarget(witness);
            wither.setTarget(org.bukkit.entity.Wither.Head.CENTER, witness);
        }
        fireSalvo(wither, s.summonSalvo);
        plugin.getLogger().fine("Horde Wither summoned at " + spawnAt);
        return wither;
    }

    /** Hurls a short burst of charged skulls so the arrival is felt immediately. */
    private void fireSalvo(Wither wither, int salvo) {
        for (int i = 0; i < salvo; i++) {
            WitherSkull skull = wither.launchProjectile(WitherSkull.class);
            if (skull == null) {
                continue;
            }
            skull.setCharged(true);
        }
    }

    /**
     * Finds a spot for the Wither that will not suffocate it inside the floor.
     *
     * <p>Vanilla places the Warden by scanning upward from the shrieker. The Wither is
     * larger, so this raises it a little and verifies the head room first.
     */
    private Location safeSpawnLocation(Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return origin;
        }
        Location candidate = origin.clone();
        for (int attempt = 0; attempt < 6; attempt++) {
            if (candidate.getBlock().isPassable() && candidate.clone().add(0, 1, 0).getBlock().isPassable()) {
                return candidate.add(0, 0.5D, 0);
            }
            candidate.add(0, 1, 0);
        }
        return origin.clone().add(0, 1, 0);
    }

    private static Player findNearbyPlayer(Location location, double radius) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        Player closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(location);
            if (distance <= radius * radius && distance < closestDistance) {
                closestDistance = distance;
                closest = player;
            }
        }
        return closest;
    }

    /** Drops counters for shriekers nobody has touched recently. */
    public void tick() {
        int now = Bukkit.getCurrentTick();
        Iterator<Map.Entry<Long, Integer>> iterator = lastTouch.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Integer> entry = iterator.next();
            if (now - entry.getValue() > IDLE_RESET_TICKS) {
                iterator.remove();
                shriekCounts.remove(entry.getKey());
                lastSummonTick.remove(entry.getKey());
            }
        }
    }

    /** Clears all tracked shrieker state. */
    public void clear() {
        shriekCounts.clear();
        lastSummonTick.clear();
        lastTouch.clear();
    }
}
