package com.mobwithers;

import java.util.EnumSet;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.SpawnCategory;

/**
 * Prevents the catastrophic water-mob spawn loop.
 *
 * <h2>The failure this exists to stop</h2>
 * Vanilla tracks water-dwelling mobs under their own category caps
 * ({@code WATER_AMBIENT} for squid, {@code WATER_ANIMAL} for fish and dolphins,
 * {@code WATER_UNDERGROUND_CREATURE} for axolotls/glow squid). Those caps are separate from
 * the {@code MONSTER} cap.
 *
 * <p>If a squid is replaced by a Wither, the Wither no longer belongs to
 * {@code WATER_AMBIENT}. The water cap therefore never fills, the spawner keeps finding a
 * free slot, and the server keeps converting another squid into another Wither. Nothing
 * in that chain ever reduces the number of available slots, so it runs unbounded: within
 * seconds the server is spawning thousands of Withers, each with particle and projectile
 * work attached, and the process dies.
 *
 * <p>Zeroing the water caps removes the free slot the loop depends on, so the loop cannot
 * begin. It is a blunt instrument, but it is the only reliable fix from the Bukkit API
 * because the cap accounting happens inside the server's natural-spawner before plugins
 * see anything.
 *
 * <p>The cap is re-applied on a timer because {@code /gamerule}, datapacks and other
 * plugins can all raise it again mid-session.
 */
public final class WaterSpawnGuard {

    /**
     * Categories that must never be allowed to accumulate, either because they are the loop
     * trigger (water mobs) or because they share the same accounting weakness (ambient bats).
     */
    private static final Set<SpawnCategory> LYING_CATEGORIES =
            EnumSet.of(SpawnCategory.WATER_AMBIENT, SpawnCategory.WATER_ANIMAL,
                    SpawnCategory.WATER_UNDERGROUND_CREATURE, SpawnCategory.AXOLOTL);

    private final MobWithersPlugin plugin;
    private int taskId = -1;

    public WaterSpawnGuard(MobWithersPlugin plugin) {
        this.plugin = plugin;
    }

    /** Applies the caps immediately and schedules the enforcement timer. */
    public void start() {
        stop();
        enforceNow();
        Settings s = plugin.settings();
        if (!s.disableWaterSpawning && !s.disableAmbientSpawning) {
            return;
        }
        // The guard is cheap, but it still runs on a timer rather than every tick because
        // the caps only change through explicit administrative action.
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin,
                this::enforceNow, s.enforceIntervalTicks, s.enforceIntervalTicks);
    }

    /** Cancels the enforcement timer. */
    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    /**
     * Zeroes every guarded spawn cap on every world.
     *
     * @return the number of worlds that were changed.
     */
    public int enforceNow() {
        Settings s = plugin.settings();
        int changed = 0;
        for (World world : Bukkit.getWorlds()) {
            boolean touched = false;
            if (s.disableWaterSpawning) {
                touched |= zero(world, LYING_CATEGORIES);
            }
            if (s.disableAmbientSpawning) {
                touched |= zero(world, EnumSet.of(SpawnCategory.AMBIENT));
            }
            if (touched) {
                changed++;
            }
        }
        return changed;
    }

    /** Zeroes a specific category if it is not already zero. */
    private static boolean zero(World world, Set<SpawnCategory> categories) {
        boolean changed = false;
        for (SpawnCategory category : categories) {
            if (world.getSpawnLimit(category) != 0) {
                world.setSpawnLimit(category, 0);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Reports whether the guard is currently the only thing keeping the loop closed.
     *
     * <p>A non-zero water cap means the server has been mutated since the last enforcement
     * pass, which is exactly the window the runaway spawn needs. Callers use this to warn
     * rather than to block.
     */
    public boolean capsAreZeroed() {
        for (World world : Bukkit.getWorlds()) {
            for (SpawnCategory category : LYING_CATEGORIES) {
                if (world.getSpawnLimit(category) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Human readable snapshot for the {@code /mobwithers status} output. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (World world : Bukkit.getWorlds()) {
            sb.append(world.getName())
                    .append(" waterAmbient=").append(world.getSpawnLimit(SpawnCategory.WATER_AMBIENT))
                    .append(" waterAnimal=").append(world.getSpawnLimit(SpawnCategory.WATER_ANIMAL))
                    .append(" waterUnderground=")
                    .append(world.getSpawnLimit(SpawnCategory.WATER_UNDERGROUND_CREATURE))
                    .append(" axolotl=").append(world.getSpawnLimit(SpawnCategory.AXOLOTL))
                    .append(" ambient=").append(world.getSpawnLimit(SpawnCategory.AMBIENT));
        }
        return sb.toString();
    }
}
