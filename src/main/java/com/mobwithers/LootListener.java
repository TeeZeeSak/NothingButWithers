package com.mobwithers;

import java.util.List;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Swaps a converted Wither's drops for its original mob's loot table.
 *
 * <p>Runs at {@link EventPriority#HIGH} rather than {@code HIGHEST} so other plugins can
 * still observe or veto the vanilla drops before they are replaced.
 *
 * <p>{@code EntityDeathEvent} fires before the entity is actually removed, so the persisted
 * data written at conversion time is still readable here.
 */
public final class LootListener implements Listener {

    private final MobWithersPlugin plugin;

    public LootListener(MobWithersPlugin plugin) {
        this.plugin = plugin;
    }

    /** Replaces drops and experience for converted Withers. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!(dead instanceof Wither) || dead.getType() != org.bukkit.entity.EntityType.WITHER) {
            return;
        }
        if (!plugin.witherIndex().isConverted(dead)) {
            // A real, player-summoned Wither keeps its vanilla Nether Star and XP.
            return;
        }

        Player killer = dead.getKiller();
        plugin.loot().applyConvertedDrops(dead, event.getDrops(), killer);

        if (plugin.settings().overwriteWitherExperience) {
            event.setDroppedExp(plugin.loot().experienceFor(dead));
        }
        plugin.witherIndex().unregister(dead);
    }
}
