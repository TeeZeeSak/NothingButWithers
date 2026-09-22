package com.mobwithers;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.GameEvent;

import io.papermc.paper.event.entity.EntityBreakEvent;

/**
 * Handles the block- and event-driven spawn paths plus terrain damage.
 *
 * <p>Three responsibilities:
 *
 * <ul>
 *   <li><b>Sculk shriekers.</b> {@code BlockReceiveGameEvent} with {@link GameEvent#SHRIEK}
 *       is the only stable Bukkit hook for an activation, because the shrieker's internal
 *       counter is not exposed. {@link SculkReplacement} reproduces the count.</li>
 *   <li><b>Warden suppression.</b> Cancelling the Warden at {@code LOWEST} guarantees the
 *       Horde Wither is a replacement rather than an addition.</li>
 *   <li><b>Terrain clamping.</b> Wither explosions and melee block-breaking are bounded by
 *       {@link TerrainGuard} and suspended while TPS is low.</li>
 * </ul>
 *
 * <p>Infested blocks (silverfish) need no dedicated spawn handling: the engine removes the
 * block and then creates the silverfish with reason {@code SILVERFISH_BLOCK}, which
 * {@link SpawnListener} already converts. This listener only counts the breaks for the
 * admin report.
 */
public final class BlockEventListener implements Listener {

    private final MobWithersPlugin plugin;

    public BlockEventListener(MobWithersPlugin plugin) {
        this.plugin = plugin;
    }

    /** Counts sculk shrieker activations and summons a Horde Wither on the threshold. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameEvent(BlockReceiveGameEvent event) {
        if (event.getEvent() != GameEvent.SHRIEK) {
            return;
        }
        if (event.getBlock().getType() != Material.SCULK_SHRIEKER) {
            return;
        }
        if (plugin.sculk().onShriek(event.getBlock().getLocation(), event.getEntity()) != null) {
            plugin.stats().hordeSummon();
        }
    }

    /**
     * Suppresses the vanilla Warden when configured.
     *
     * <p>Runs at {@code LOWEST} and without {@code ignoreCancelled} so it sees the spawn
     * first and can veto it before any other listener reacts to a Warden that will not exist.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onWardenSpawn(CreatureSpawnEvent event) {
        if (!plugin.settings().disableWardenSpawn) {
            return;
        }
        if (event.getEntityType() == EntityType.WARDEN) {
            event.setCancelled(true);
        }
    }

    /**
     * Clamps Wither-caused block destruction.
     *
     * <p>This is the single most important performance guard: an unclamped Wither inside a
     * Woodland Mansion removes hundreds of blocks per charge.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof Wither wither)) {
            return;
        }
        if (!plugin.witherIndex().isConverted(wither)) {
            return;
        }
        int removed = plugin.terrain().clamp(wither, event.blockList());
        if (removed > 0) {
            plugin.stats().terrainSkipped(removed);
        }
    }

    /**
     * Stops converted Withers from breaking protected blocks with their melee charge.
     *
     * <p>This path does not go through the explosion block list, so it needs its own guard.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityBreak(EntityBreakEvent event) {
        if (!(event.getEntity() instanceof Wither wither)) {
            return;
        }
        if (!plugin.witherIndex().isConverted(wither)) {
            return;
        }
        if (!plugin.tpsGuard().allowTerrainDamage(plugin.settings())) {
            event.setCancelled(true);
            return;
        }
        if (plugin.terrain().isProtected(event.getEntity().getLocation().getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Keeps Withers from converting protected blocks into air through other mechanics. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Wither)) {
            return;
        }
        if (plugin.terrain().isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Counts infested-block breaks; the resulting silverfish is converted by the spawn listener. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakInfested(BlockBreakEvent event) {
        if (!isInfested(event.getBlock().getType())) {
            return;
        }
        plugin.stats().infestedBreak();
        if (plugin.debug()) {
            plugin.getLogger().info("Infested block broken by " + event.getPlayer().getName()
                    + " at " + event.getBlock().getLocation() + "; the silverfish becomes a Wither");
        }
    }

    /** True for the infested-block family that hides silverfish. */
    public static boolean isInfested(Material material) {
        return material == Material.INFESTED_STONE
                || material == Material.INFESTED_COBBLESTONE
                || material == Material.INFESTED_STONE_BRICKS
                || material == Material.INFESTED_MOSSY_STONE_BRICKS
                || material == Material.INFESTED_CRACKED_STONE_BRICKS
                || material == Material.INFESTED_CHISELED_STONE_BRICKS
                || material == Material.INFESTED_DEEPSLATE;
    }
}
