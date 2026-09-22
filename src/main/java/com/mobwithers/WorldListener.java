package com.mobwithers;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * World lifecycle and per-Wither combat behaviour.
 *
 * <p>Three jobs:
 *
 * <ul>
 *   <li>Convert mobs in chunks that already existed before the plugin was installed, so a
 *       world generated in a previous session does not keep its original mobs forever.</li>
 *   <li>Re-apply the water and ambient spawn-cap suppression whenever a world loads, since
 *       caps are per world and a new world starts at vanilla defaults.</li>
 *   <li>Enforce the armour phase at half health and stop converted Withers from losing
 *       interest in a player once they have locked on.</li>
 * </ul>
 */
public final class WorldListener implements Listener {

    private final MobWithersPlugin plugin;

    public WorldListener(MobWithersPlugin plugin) {
        this.plugin = plugin;
    }

    /** Re-applies the water/ambient cap suppression to a newly loaded world. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        plugin.waterGuard().enforceNow();
    }

    /**
     * Converts pre-existing mobs as their chunks load.
     *
     * <p>Runs at {@code MONITOR} so the entities are fully initialised and after any other
     * plugin has had its say. Work is bounded per chunk so a chunk full of a mob farm cannot
     * cause a tick spike.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!plugin.settings().scanLoadedChunks) {
            return;
        }
        int converted = plugin.scanLoadedEntities(event.getEntities());
        if (converted > 0) {
            plugin.getLogger().fine("Converted " + converted + " pre-existing mobs in chunk "
                    + event.getChunk().getX() + "," + event.getChunk().getZ());
        }
    }

    /**
     * Keeps a locked-on Wither locked on.
     *
     * <p>Converted Withers are made persistent so they cannot despawn out from under a
     * player who is mid-fight, mirroring the vanilla Wither's refusal to give up once it has
     * acquired a target.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Wither wither) || !plugin.witherIndex().isConverted(wither)) {
            return;
        }
        if (event.getTarget() instanceof Player) {
            wither.setRemoveWhenFarAway(false);
        }
    }

    /**
     * Triggers the armour phase when a converted Wither is about to drop below half health.
     *
     * <p>The projection uses the event's final damage, so the phase engages on the hit that
     * crosses the threshold rather than the hit after it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Wither wither) || !plugin.witherIndex().isConverted(wither)) {
            return;
        }
        if (!plugin.settings().enforceHalfHealthPhase) {
            return;
        }
        var health = wither.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double max = health == null ? WitherTuning.WITHER_HEALTH : health.getValue();
        if (wither.getHealth() - event.getFinalDamage() <= max / 2.0D) {
            WitherTuning.applyArmourPhase(wither);
        }
    }

    /** Unused reference kept so structure-based conversion rules have a documented hook. */
    static boolean isConvertible(org.bukkit.entity.Entity entity) {
        return entity instanceof Mob && entity.getType() != EntityType.WITHER;
    }
}
