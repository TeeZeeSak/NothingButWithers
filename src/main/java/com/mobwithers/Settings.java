package com.mobwithers;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

/**
 * Immutable snapshot of {@code config.yml}.
 *
 * <p>{@link #load(FileConfiguration)} runs on enable and on every {@code /mobwithers reload},
 * so no runtime code path reads the live configuration twice for one decision.
 */
public final class Settings {

    public final boolean enabled;

    public final boolean convertNatural;
    public final boolean convertSpawners;
    public final boolean convertBlockEvents;
    public final boolean convertExternal;
    public final Set<EntityType> excludedTypes;
    public final boolean skipNamedMobs;
    public final boolean skipTamedMobs;
    public final boolean skipVehicles;
    public final boolean scanLoadedChunks;
    public final String fallbackLootTable;

    public final boolean inheritCustomName;
    public final boolean inheritSpawnerEntityType;

    public final boolean disableWaterSpawning;
    public final boolean disableAmbientSpawning;
    public final int enforceIntervalTicks;

    public final int maxWithersPerWorld;
    public final int maxWithersPerChunk;
    public final double minTpsToConvert;
    public final double aiFreezeDistance;
    public final int aiCheckIntervalTicks;

    public final boolean terrainEnabled;
    public final double terrainDisableBelowTps;
    public final int maxBlocksPerExplosion;
    public final Set<Material> protectedBlocks;

    public final int shriekerActivations;
    public final double shriekerPlayerRadius;
    public final boolean disableWardenSpawn;
    public final int summonSalvo;

    public final boolean suppressNetherStar;
    public final boolean overwriteWitherExperience;
    public final boolean useLooting;
    public final boolean rollOriginalLootTable;

    public final boolean enforceHalfHealthPhase;
    public final double combatSpeedMultiplier;

    public final boolean bossBarEnabled;
    public final int bossBarIntervalTicks;
    public final String bossBarTitle;
    public final boolean bossBarAggregate;
    public final int bossBarAggregateMinimum;

    private Settings(FileConfiguration c) {
        this.enabled = c.getBoolean("enabled", true);

        this.convertNatural = c.getBoolean("conversion.natural-mobs", true);
        this.convertSpawners = c.getBoolean("conversion.spawner-mobs", true);
        this.convertBlockEvents = c.getBoolean("conversion.block-event-mobs", true);
        this.convertExternal = c.getBoolean("conversion.external-mobs", true);
        this.excludedTypes = readTypes(c, "conversion.excluded-types");
        this.skipNamedMobs = c.getBoolean("conversion.skip-named-mobs", true);
        this.skipTamedMobs = c.getBoolean("conversion.skip-tamed-mobs", true);
        this.skipVehicles = c.getBoolean("conversion.skip-vehicles", true);
        this.scanLoadedChunks = c.getBoolean("conversion.scan-loaded-chunks", true);
        this.fallbackLootTable = c.getString("conversion.fallback-loot-table", "minecraft:entities/zombie");

        this.inheritCustomName = c.getBoolean("inheritance.custom-name", true);
        this.inheritSpawnerEntityType = c.getBoolean("inheritance.spawner-entity-type", true);

        this.disableWaterSpawning = c.getBoolean("water.disable-water-mob-spawning", true);
        this.disableAmbientSpawning = c.getBoolean("water.disable-ambient-mob-spawning", true);
        this.enforceIntervalTicks = Math.max(20, c.getInt("water.enforce-interval-ticks", 600));

        this.maxWithersPerWorld = Math.max(1, c.getInt("performance.max-withers-per-world", 48));
        this.maxWithersPerChunk = Math.max(1, c.getInt("performance.max-withers-per-chunk", 2));
        this.minTpsToConvert = c.getDouble("performance.min-tps-to-convert", 12.0D);
        this.aiFreezeDistance = c.getDouble("performance.ai-freeze-distance", 48.0D);
        this.aiCheckIntervalTicks = Math.max(1, c.getInt("performance.ai-check-interval-ticks", 40));

        this.terrainEnabled = c.getBoolean("terrain.enabled", true);
        this.terrainDisableBelowTps = c.getDouble("terrain.disable-below-tps", 16.0D);
        this.maxBlocksPerExplosion = Math.max(0, c.getInt("terrain.max-blocks-destroyed-per-explosion", 3));
        this.protectedBlocks = readMaterials(c, "terrain.protected-blocks");

        this.shriekerActivations = Math.max(1, c.getInt("sculk.shrieker-activations", 4));
        this.shriekerPlayerRadius = c.getDouble("sculk.shrieker-player-radius", 16.0D);
        this.disableWardenSpawn = c.getBoolean("sculk.disable-warden-spawn", true);
        this.summonSalvo = Math.max(0, c.getInt("sculk.summon-salvo", 1));

        this.suppressNetherStar = c.getBoolean("loot.suppress-nether-star", true);
        this.overwriteWitherExperience = c.getBoolean("loot.overwrite-wither-experience", true);
        this.useLooting = c.getBoolean("loot.use-looting", true);
        this.rollOriginalLootTable = c.getBoolean("loot.roll-original-loot-table", true);

        this.enforceHalfHealthPhase = c.getBoolean("combat.enforce-phase-at-half-health", true);
        this.combatSpeedMultiplier = Math.max(0.1D, c.getDouble("combat.combat-speed-multiplier", 1.0D));

        this.bossBarEnabled = c.getBoolean("boss-bar.enabled", true);
        this.bossBarIntervalTicks = Math.max(1, c.getInt("boss-bar.interval-ticks", 20));
        this.bossBarTitle = c.getString("boss-bar.title", "&8Horde Withers");
        this.bossBarAggregate = c.getBoolean("boss-bar.aggregate", true);
        this.bossBarAggregateMinimum = Math.max(0, c.getInt("boss-bar.aggregate-minimum", 1));
    }

    public static Settings load(FileConfiguration c) {
        return new Settings(c);
    }

    /**
     * Types that must never be converted, regardless of what the config says.
     *
     * <p>{@code ENDER_DRAGON} is here because converting it makes the game impossible to
     * finish. {@code WITHER} is here to make the replacement non-recursive even if the
     * persisted-data marker is ever lost. These are enforced in code rather than trusted to
     * the config file so that an upgrade which drops the {@code excluded-types} key cannot
     * silently break a world.
     */
    private static final Set<EntityType> MANDATORY_EXCLUSIONS =
            EnumSet.of(EntityType.ENDER_DRAGON, EntityType.WITHER);

    /**
     * Blocks that are never destroyed by terrain erosion, merged with the config list.
     *
     * <p>{@code BEDROCK} and the command blocks are included so a Wither can never delete
     * the world floor or an operator's command block, which would be unrecoverable.
     */
    private static final Set<Material> MANDATORY_PROTECTED = EnumSet.of(
            Material.BEDROCK, Material.BARRIER, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.JIGSAW,
            Material.END_PORTAL_FRAME, Material.OBSIDIAN, Material.ENCHANTING_TABLE,
            Material.RESPAWN_ANCHOR, Material.BEACON, Material.NETHERITE_BLOCK);

    private static Set<EntityType> readTypes(FileConfiguration c, String path) {
        var names = c.getStringList(path);
        Set<EntityType> out = EnumSet.noneOf(EntityType.class);
        out.addAll(MANDATORY_EXCLUSIONS);
        for (String raw : names) {
            try {
                out.add(EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknownType) {
                // A typo in the exclusion list must not stop the server from starting.
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static Set<Material> readMaterials(FileConfiguration c, String path) {
        var names = c.getStringList(path);
        Set<Material> out = new HashSet<>(MANDATORY_PROTECTED);
        for (String raw : names) {
            Material material = Material.matchMaterial(raw.trim());
            if (material != null) {
                out.add(material);
            }
        }
        return Collections.unmodifiableSet(out);
    }
}
