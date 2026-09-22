package com.mobwithers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Handles the "retained loot tables" requirement.
 *
 * <p>Converted Withers carry the namespaced key of their original mob's loot table in
 * persisted data. On death that table is rolled instead of the Wither's own drops, with the
 * killer's Looting level applied through {@link LootContext.Builder#lootingModifier(int)}.
 */
public final class LootService {

    private final MobWithersPlugin plugin;
    private final EntityKeys keys;
    private final Random random = new Random();

    public LootService(MobWithersPlugin plugin, EntityKeys keys) {
        this.plugin = plugin;
        this.keys = keys;
    }

    /**
     * Rolls the stored loot table for a dead converted Wither.
     *
     * @param wither the dead entity, which must still carry its persisted data
     * @param drops  the mutable drop list from the death event
     * @param killer the player that landed the killing blow, may be {@code null}
     */
    public void applyConvertedDrops(LivingEntity wither, List<ItemStack> drops, Player killer) {
        Settings s = plugin.settings();

        if (s.suppressNetherStar) {
            drops.removeIf(stack -> stack != null && stack.getType() == Material.NETHER_STAR);
        }

        if (!s.rollOriginalLootTable) {
            return;
        }

        LootTable table = resolveTable(wither, s);
        if (table == null) {
            return;
        }

        // The source mob's table replaces the Wither table entirely; otherwise every kill
        // would drop a Nether Star's worth of value on top of the intended loot.
        drops.clear();
        Collection<ItemStack> rolled = roll(table, wither.getLocation(), wither, killer, s);
        for (ItemStack stack : rolled) {
            if (stack != null && !stack.getType().isAir()) {
                drops.add(stack);
            }
        }
    }

    /** Rolls a table, letting vanilla derive the Looting level from the killer. */
    public Collection<ItemStack> roll(LootTable table, Location location, Entity looted, Player killer,
                                      Settings s) {
        LootContext.Builder builder = new LootContext.Builder(location)
                .lootedEntity(looted)
                .luck(0.0F);
        // Builder#lootingModifier is deprecated and non-functional in current Minecraft. The
        // supported path is to hand vanilla the killer and let it read the Looting level off
        // the killer's equipped item itself, which is exactly what vanilla drops do.
        if (s.useLooting && killer != null) {
            builder.killer(killer);
        }
        LootContext context = builder.build();
        try {
            Collection<ItemStack> rolled = table.populateLoot(random, context);
            return rolled == null ? List.of() : new ArrayList<>(rolled);
        } catch (RuntimeException | LinkageError failed) {
            // A table that exists but refuses to roll is a bug worth surfacing, not a quiet no-drop.
            plugin.getLogger().warning("Loot roll failed for " + table.getKey() + ": " + failed);
            for (StackTraceElement frame : failed.getStackTrace()) {
                plugin.getLogger().warning("    at " + frame);
            }
            return List.of();
        }
    }

    /** Sums the Looting level across both hands, matching vanilla behaviour. */
    public static int lootingLevel(Player player) {
        int level = 0;
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main != null) {
            level = Math.max(level, main.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.LOOTING));
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off != null) {
            level = Math.max(level, off.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.LOOTING));
        }
        return level;
    }

    /**
     * Experience the source mob would have granted.
     *
     * <p>{@code LootTable} does not expose the experience payout, and vanilla entity tables
     * are the only place it lives, so a small representative value is derived from the
     * entity type instead of guessing from the table contents.
     */
    public int experienceFor(LivingEntity wither) {
        PersistentDataContainer pdc = wither.getPersistentDataContainer();
        String name = pdc.get(keys.originalMob, PersistentDataType.STRING);
        if (name == null) {
            return 0;
        }
        try {
            org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf(name);
            // Bosses and minibosses are worth more than the average mob; everything else
            // uses the standard 5 point hostile payout.
            return switch (type) {
                case WARDEN, ELDER_GUARDIAN, RAVAGER, EVOKER, PIGLIN_BRUTE, WITHER_SKELETON,
                     ENDERMAN, BLAZE, GHAST -> 10;
                case ENDER_DRAGON -> 500;
                default -> 5;
            };
        } catch (IllegalArgumentException unknown) {
            return 5;
        }
    }

    private LootTable resolveTable(LivingEntity wither, Settings s) {
        PersistentDataContainer pdc = wither.getPersistentDataContainer();
        String stored = pdc.get(keys.originalLootTable, PersistentDataType.STRING);
        if (stored != null) {
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(stored);
            LootTable table = MobLootTables.findTable(key);
            if (table != null) {
                return table;
            }
        }
        // A mob with no loot table of its own (or one whose table was removed) falls back
        // to the configured default rather than dropping the vanilla Wither loot.
        return MobLootTables.parseTable(s.fallbackLootTable);
    }

    /** Counts the drops the service produced, for the admin command. */
    public int dropCount(LivingEntity wither) {
        LootTable table = resolveTable(wither, plugin.settings());
        return table == null ? 0 : roll(table, wither.getLocation(), wither, null, plugin.settings()).size();
    }
}
