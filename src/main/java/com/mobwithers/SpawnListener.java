package com.mobwithers;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.entity.TrialSpawnerSpawnEvent;

/**
 * Intercepts every spawn path a mob can take and hands it to {@link ConversionService}.
 *
 * <h2>Why several events</h2>
 * Bukkit has no single "a mob spawned" hook. The paths that matter are:
 *
 * <ul>
 *   <li>{@link CreatureSpawnEvent} - natural spawning, chunk generation, structure placement,
 *       breeding and most plugin spawns. This covers the vast majority.</li>
 *   <li>{@link SpawnerSpawnEvent} - monster spawners. It extends {@code EntitySpawnEvent},
 *       which is <em>not</em> a {@code CreatureSpawnEvent}, so it needs its own handler.</li>
 *   <li>{@link TrialSpawnerSpawnEvent} - trial chambers and ominous item spawners. Again a
 *       sibling of {@code CreatureSpawnEvent}, not a subclass.</li>
 * </ul>
 *
 * <p>Handlers run at {@link EventPriority#HIGHEST} so other plugins get the first look at a
 * spawn and can cancel it themselves; a spawn another plugin already cancelled is skipped
 * via {@code ignoreCancelled}, so no Wither is created for a mob that will never exist.
 *
 * <p><b>Re-entrancy.</b> The replacement Wither is created with {@code World#spawn}, which
 * fires these events again. {@link ConversionService#replacementFor} refuses to touch
 * anything that is already a Wither, so the recursion terminates after exactly one hop.
 */
public final class SpawnListener implements Listener {

    private final MobWithersPlugin plugin;

    public SpawnListener(MobWithersPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Natural, chunk-generation, structure, breeding and plugin-driven mob spawns.
     *
     * <p>The event is cancelled and a Wither is spawned in the same location. Cancelling
     * first means the original mob never enters the world, so there is no window in which
     * both entities exist and no duplicate AI ticking.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        handle(event.getEntity(), event, event.getSpawnReason(), null, false);
    }

    /** Monster spawner spawns: dungeons, blaze spawners, silverfish spawners. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent event) {
        Entity source = event.getEntity();
        EntityType configured = SpawnerRegistry.typeOf(event.getSpawner().getBlock());
        handle(source, event, SpawnReason.SPAWNER, configured, true);
    }

    /**
     * Records what a spawner is about to emit, before the entity exists.
     *
     * <p>{@code SpawnerSpawnEvent} already carries the spawner block, but a trial spawner
     * rerolls its reward from a configured pool, and the entity that briefly exists may not
     * match the configured type. Capturing the type here means the eventual Wither inherits
     * the loot table the player actually expects to farm.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreSpawnerSpawn(com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent event) {
        plugin.spawnerRegistry().remember(event.getSpawnerLocation().getBlock(), event.getType());
    }

    /** Trial chamber and ominous item spawner spawns. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTrialSpawnerSpawn(TrialSpawnerSpawnEvent event) {
        Block block = event.getTrialSpawner().getBlock();
        // Trial spawners reroll their reward from a configured pool, so the type read from
        // the block is more accurate than the entity that briefly existed.
        EntityType configured = plugin.spawnerRegistry().recall(block);
        if (configured == null) {
            configured = SpawnerRegistry.typeOf(block);
        }
        handle(event.getEntity(), event, SpawnReason.TRIAL_SPAWNER, configured, true);
    }

    /**
     * Catches mobs placed directly into the world by other plugins or commands.
     *
     * <p>These arrive as a plain {@link EntitySpawnEvent} rather than a creature spawn, so
     * they are handled here. The spawn reason is unknown, so only entities the config marks
     * as external are converted.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (event instanceof CreatureSpawnEvent
                || event instanceof SpawnerSpawnEvent
                || event instanceof TrialSpawnerSpawnEvent) {
            return; // Already handled by the more specific listeners above.
        }
        handle(event.getEntity(), event, SpawnReason.CUSTOM, null, false);
    }

    /**
     * Shared replacement routine.
     *
     * @param entity     the mob the server is about to add
     * @param event      the cancellable spawn event
     * @param reason     spawn reason recorded on the Wither
     * @param sourceType the spawner's configured mob, or {@code null} to use the entity's own type
     * @param spawnerBorn whether the spawn came from a spawner block
     */
    private void handle(Entity entity, org.bukkit.event.Cancellable event, SpawnReason reason,
                        EntityType sourceType, boolean spawnerBorn) {
        if (!(entity instanceof Mob mob)) {
            return;
        }
        if (plugin.conversion().replacementFor(mob, reason) == null) {
            return;
        }
        boolean useSpawnerType = sourceType != null && sourceType != mob.getType()
                && SpawnerRegistry.isSafeMarkerType(sourceType);
        event.setCancelled(true);
        if (plugin.debug()) {
            EntityType recordedType = useSpawnerType ? sourceType : mob.getType();
            plugin.getLogger().info("Converting " + mob.getType() + " -> Wither at "
                    + mob.getLocation().toVector() + " (reason=" + reason + ", recorded=" + recordedType + ")");
        }
        // schedule rather than convert: see ConversionService#schedule.
        plugin.conversion().schedule(mob, EntityType.WITHER, reason, spawnerBorn,
                useSpawnerType ? sourceType : null);
    }
}
