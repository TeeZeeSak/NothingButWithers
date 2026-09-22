package com.mobwithers;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;

/**
 * Freezes the AI of converted Withers that are far from every player.
 *
 * <h2>Why this matters more than it looks</h2>
 * A Wither's flight is expensive: it has three heads, each running its own targeting and
 * projectile goal, plus a movement goal that constantly raycasts terrain for ground clearance.
 * With dozens loaded, that work dominates the tick even when no player is anywhere near.
 *
 * <p>Withers in a chunk that is loaded but far from any player cannot affect gameplay, so
 * their goals are disabled with {@link org.bukkit.entity.LivingEntity#setAI(boolean)} and
 * re-enabled the moment a player comes within range. Physics still runs, so a frozen Wither
 * keeps falling and cannot be pushed out of the world.
 *
 * <p>The scan is scheduled rather than per-tick, and the per-world entity list is only
 * rebuilt each pass, so the cost scales with the AI-check interval rather than with tick rate.
 */
public final class AiLimiter {

    private final MobWithersPlugin plugin;
    private int taskId = -1;

    public AiLimiter(MobWithersPlugin plugin) {
        this.plugin = plugin;
    }

    /** Schedules the periodic AI sweep. */
    public void start() {
        stop();
        Settings s = plugin.settings();
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::sweep,
                s.aiCheckIntervalTicks, s.aiCheckIntervalTicks);
    }

    /** Cancels the sweep and restores AI on every converted Wither. */
    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Wither wither : world.getEntitiesByClass(Wither.class)) {
                if (plugin.witherIndex().isConverted(wither) && !wither.hasAI()) {
                    wither.setAI(true);
                }
            }
        }
    }

    /** One freeze/thaw pass over every loaded world. */
    private void sweep() {
        Settings s = plugin.settings();
        if (!s.enabled) {
            return;
        }
        double rangeSquared = s.aiFreezeDistance * s.aiFreezeDistance;
        long toggled = 0;
        for (World world : Bukkit.getWorlds()) {
            List<Player> players = world.getPlayers();
            if (players.isEmpty()) {
                // Nobody in the world at all: freeze everything we own.
                for (Wither wither : world.getEntitiesByClass(Wither.class)) {
                    if (plugin.witherIndex().isConverted(wither) && freeze(wither)) {
                        toggled++;
                    }
                }
                continue;
            }
            for (Wither wither : world.getEntitiesByClass(Wither.class)) {
                if (!plugin.witherIndex().isConverted(wither)) {
                    continue;
                }
                // A Wither in the middle of an attack must keep ticking or it will hang
                // motionless in front of the player it is fighting.
                if (wither.getTarget() != null) {
                    if (thaw(wither)) {
                        toggled++;
                    }
                    continue;
                }
                if (isNearAnyPlayer(wither.getLocation(), players, rangeSquared)) {
                    if (thaw(wither)) {
                        toggled++;
                    }
                } else if (freeze(wither)) {
                    toggled++;
                }
            }
        }
        if (toggled > 0) {
            plugin.stats().frozenAi(toggled);
        }
    }

    private static boolean isNearAnyPlayer(Location location, List<Player> players, double rangeSquared) {
        for (Player player : players) {
            if (player.getLocation().distanceSquared(location) <= rangeSquared) {
                return true;
            }
        }
        return false;
    }

    private static boolean freeze(Wither wither) {
        if (!wither.hasAI()) {
            return false;
        }
        wither.setAI(false);
        return true;
    }

    private static boolean thaw(Wither wither) {
        if (wither.hasAI()) {
            return false;
        }
        wither.setAI(true);
        return true;
    }

    /** Number of converted Withers currently frozen, for the admin report. */
    public int frozenCount() {
        int frozen = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Wither wither : world.getEntitiesByClass(Wither.class)) {
                if (plugin.witherIndex().isConverted(wither) && !wither.hasAI()) {
                    frozen++;
                }
            }
        }
        return frozen;
    }
}
