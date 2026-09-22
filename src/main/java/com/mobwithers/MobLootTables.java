package com.mobwithers;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.LootTables;

/**
 * Resolves the vanilla loot table that belongs to a mob.
 *
 * <p>Bukkit mirrors entity loot tables through the {@link LootTables} enum, whose keys live
 * under {@code minecraft:entities/...}. That enum is used purely for <em>discovery</em> of
 * the namespaced key; the actual table is fetched with {@link Bukkit#getLootTable}.
 *
 * <p>Discovery happens from the mob's {@link EntityType} before the mob is removed, so it
 * never depends on a live entity. Results are cached, including negative results.
 */
public final class MobLootTables {

    /** EntityType -> loot table key, with {@code null} values recording "no entity table". */
    private static final Map<EntityType, NamespacedKey> CACHE = new ConcurrentHashMap<>();

    private MobLootTables() {
    }

    /**
     * Finds the namespaced key of the loot table associated with {@code type}.
     *
     * @return the loot table key, or {@code null} when the mob has no entity loot table
     *         (for example the Warden, which drops hard-coded items rather than a table).
     */
    public static NamespacedKey keyFor(EntityType type) {
        return CACHE.computeIfAbsent(type, MobLootTables::discover);
    }

    /** Convenience overload resolving straight from a live entity. */
    public static NamespacedKey keyFor(Entity entity) {
        return keyFor(entity.getType());
    }

    /**
     * Resolves the loot table for an entity type.
     *
     * @return the table, or {@code null} when the mob has none.
     */
    public static LootTable tableFor(EntityType type) {
        NamespacedKey key = keyFor(type);
        return key == null ? null : findTable(key);
    }

    /** Convenience overload resolving straight from a live entity. */
    public static LootTable tableFor(Entity entity) {
        return tableFor(entity.getType());
    }

    /**
     * Turns a namespaced key such as {@code minecraft:entities/blaze} into a live table.
     *
     * @return the table, or {@code null} when the server has no table registered under the key.
     */
    public static LootTable findTable(NamespacedKey key) {
        if (key == null) {
            return null;
        }
        try {
            return Bukkit.getLootTable(key);
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** Parses a config-supplied loot table string, accepting {@code entities/zombie} or {@code zombie}. */
    public static LootTable parseTable(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key = cleaned.contains(":")
                ? NamespacedKey.fromString(cleaned)
                : NamespacedKey.minecraft(cleaned);
        if (key == null) {
            return null;
        }
        LootTable table = findTable(key);
        if (table == null && !key.getKey().contains("/")) {
            table = findTable(NamespacedKey.minecraft("entities/" + key.getKey()));
        }
        return table;
    }

    /** Converts a {@link LootTable} back into its namespaced key for persistence. */
    public static NamespacedKey keyOf(LootTable table) {
        return table == null ? null : table.getKey();
    }

    /** True when the loot table key belongs to the {@code entities/} family. */
    public static boolean isEntityTable(NamespacedKey key) {
        return key != null && key.getKey().startsWith("entities/");
    }

    private static NamespacedKey discover(EntityType type) {
        String name = type.name();
        // The overwhelming majority of mobs map 1:1 onto the enum constant name.
        NamespacedKey direct = fromEnum(name);
        if (direct != null) {
            return direct;
        }
        // Variants that exist as separate entities but not as separate enum constants.
        NamespacedKey horseVariant = fromEnum(name + "_HORSE");
        if (horseVariant != null) {
            return horseVariant;
        }
        NamespacedKey illagerVariant = fromEnum(name + "_VILLAGER");
        if (illagerVariant != null) {
            return illagerVariant;
        }
        // UNDEAD_HORSE is the only horse variant whose Bukkit name differs from its loot entry.
        if ("UNDEAD_HORSE".equals(name)) {
            return fromEnum("SKELETON_HORSE");
        }
        return null;
    }

    private static NamespacedKey fromEnum(String constant) {
        try {
            return intoKey(LootTables.valueOf(constant));
        } catch (IllegalArgumentException notAnEnumConstant) {
            return null;
        }
    }

    private static NamespacedKey intoKey(LootTables candidate) {
        NamespacedKey key = candidate.getKey();
        return isEntityTable(key) ? key : null;
    }
}
