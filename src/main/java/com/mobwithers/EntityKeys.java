package com.mobwithers;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Single place that owns every {@link NamespacedKey} used on entities.
 *
 * <p>Keys are namespaced to this plugin so they never collide with vanilla or
 * other plugins' persisted data.
 */
public final class EntityKeys {

    /** String: Bukkit {@code EntityType} name of the mob this Wither replaced. */
    public final NamespacedKey originalMob;

    /** String: namespaced loot table key rolled instead of the Wither loot table. */
    public final NamespacedKey originalLootTable;

    /** Byte: {@code 1} when this Wither was created by MobWithers. */
    public final NamespacedKey converted;

    /** String: the spawn reason that created this Wither. */
    public final NamespacedKey spawnReason;

    /** Long: the tick the Wither was converted on, for cap bookkeeping. */
    public final NamespacedKey convertedAt;

    /** Byte: {@code 1} for Withers summoned by a sculk shrieker; these never despawn. */
    public final NamespacedKey sculkSummoned;

    /** Byte: {@code 1} for Withers placed by a mob spawner or trial spawner. */
    public final NamespacedKey spawnerBorn;

    public EntityKeys(JavaPlugin plugin) {
        this.originalMob = new NamespacedKey(plugin, "original_mob");
        this.originalLootTable = new NamespacedKey(plugin, "original_loot_table");
        this.converted = new NamespacedKey(plugin, "converted");
        this.spawnReason = new NamespacedKey(plugin, "spawn_reason");
        this.convertedAt = new NamespacedKey(plugin, "converted_at");
        this.sculkSummoned = new NamespacedKey(plugin, "sculk_summoned");
        this.spawnerBorn = new NamespacedKey(plugin, "spawner_born");
    }

    /** Shared type token for one-byte boolean flags. */
    public static final PersistentDataType<Byte, Byte> BYTE = PersistentDataType.BYTE;
}
