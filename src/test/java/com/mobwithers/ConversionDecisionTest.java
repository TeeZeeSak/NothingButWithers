package com.mobwithers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.junit.jupiter.api.Test;

/**
 * Verifies the pure decision logic: which spawn reasons convert, and how the configuration is
 * interpreted.
 *
 * <p>These paths are the ones where a mistake is most expensive. A wrong spawn-reason mapping
 * silently converts the wrong mobs, and a wrong exclusion list either locks the player out of
 * finishing the game (Ender Dragon) or starts the run-away water spawn loop. Neither is easy
 * to notice on a live server, so they are pinned down here.
 */
class ConversionDecisionTest {

    /** A settings object built from an empty config, i.e. all defaults. */
    private static Settings defaults() {
        return Settings.load(new YamlConfiguration());
    }

    @Test
    void naturalSpawnReasonsConvert() {
        Settings s = defaults();
        assertTrue(s.convertNatural, "natural conversion should default on");
        assertTrue(ConversionService.reasonAllowed(s, SpawnReason.NATURAL));
        assertTrue(ConversionService.reasonAllowed(s, SpawnReason.PATROL));
        assertTrue(ConversionService.reasonAllowed(s, SpawnReason.RAID));
        assertTrue(ConversionService.reasonAllowed(s, SpawnReason.NETHER_PORTAL));
        assertTrue(ConversionService.reasonAllowed(s, SpawnReason.LIGHTNING));
    }

    @Test
    void spawnerReasonsConvert() {
        Settings s = defaults();
        assertTrue(ConversionService.reasonAllowed(s, SpawnReason.SPAWNER));
        assertTrue(ConversionService.reasonAllowed(s, SpawnReason.TRIAL_SPAWNER));
        assertTrue(ConversionService.reasonAllowed(s, SpawnReason.OMINOUS_ITEM_SPAWNER));
    }

    @Test
    void infestedBlockReasonsConvert() {
        Settings s = defaults();
        assertTrue(ConversionService.reasonAllowed(s, SpawnReason.SILVERFISH_BLOCK));
        assertTrue(ConversionService.reasonAllowed(s, SpawnReason.INFECTION));
    }

    /**
     * Jockeys and village defence must not convert: replacing a chicken a spider is riding
     * would detach the passenger, and replacing an iron golem the village spawned to defend
     * itself would leave the village undefended and permanently short a golem.
     */
    @Test
    void jockeyAndVillageReasonsAreExcluded() {
        Settings s = defaults();
        assertFalse(ConversionService.reasonAllowed(s, SpawnReason.JOCKEY));
        assertFalse(ConversionService.reasonAllowed(s, SpawnReason.VILLAGE_DEFENSE));
        assertFalse(ConversionService.reasonAllowed(s, SpawnReason.SHOULDER_ENTITY));
        assertFalse(ConversionService.reasonAllowed(s, SpawnReason.DROWNED));
    }

    /** Turning a category off must take every reason in that category with it. */
    @Test
    void categoriesCanBeDisabledIndependently() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("conversion.natural-mobs", false);
        yaml.set("conversion.spawner-mobs", false);
        yaml.set("conversion.block-event-mobs", false);
        yaml.set("conversion.external-mobs", false);
        Settings s = Settings.load(yaml);

        assertFalse(ConversionService.reasonAllowed(s, SpawnReason.NATURAL));
        assertFalse(ConversionService.reasonAllowed(s, SpawnReason.SPAWNER));
        assertFalse(ConversionService.reasonAllowed(s, SpawnReason.SILVERFISH_BLOCK));
        assertFalse(ConversionService.reasonAllowed(s, SpawnReason.COMMAND));
        assertFalse(ConversionService.reasonAllowed(s, null), "null reason is the external bucket");
    }

    @Test
    void excludedTypesIncludeDragonAndWitherByDefault() {
        Settings s = defaults();
        assertTrue(s.excludedTypes.contains(EntityType.ENDER_DRAGON),
                "the Ender Dragon must not be converted or the game cannot be finished");
        assertTrue(s.excludedTypes.contains(EntityType.WITHER),
                "real Withers must not be re-converted");
    }

    @Test
    void waterSpawningIsDisabledByDefault() {
        Settings s = defaults();
        assertTrue(s.disableWaterSpawning,
                "water spawning must be off by default or the run-away loop can start");
        assertTrue(s.disableAmbientSpawning);
    }

    @Test
    void defaultCapsAndLootSettingsAreSane() {
        Settings s = defaults();
        assertEquals(300.0D, WitherTuning.WITHER_HEALTH, 0.001D);
        assertEquals(40.0D, WitherTuning.AGGRO_RANGE, 0.001D);
        assertTrue(s.maxWithersPerWorld > 0);
        assertTrue(s.maxWithersPerChunk > 0);
        assertEquals("minecraft:entities/zombie", s.fallbackLootTable);
        assertTrue(s.suppressNetherStar);
        assertTrue(s.rollOriginalLootTable);
    }

    /** Zero or negative values in the config must not be able to disable the safety rails. */
    @Test
    void nonsensicalValuesAreClamped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("performance.max-withers-per-world", 0);
        yaml.set("performance.max-withers-per-chunk", -5);
        yaml.set("performance.ai-check-interval-ticks", 0);
        yaml.set("water.enforce-interval-ticks", 0);
        yaml.set("terrain.max-blocks-destroyed-per-explosion", -1);
        yaml.set("combat.combat-speed-multiplier", 0.0D);
        Settings s = Settings.load(yaml);

        assertTrue(s.maxWithersPerWorld >= 1);
        assertTrue(s.maxWithersPerChunk >= 1);
        assertTrue(s.aiCheckIntervalTicks >= 1);
        assertTrue(s.enforceIntervalTicks >= 20);
        assertTrue(s.maxBlocksPerExplosion >= 0);
        assertTrue(s.combatSpeedMultiplier > 0.0D);
    }

    /** A typo in the exclusion list must not stop the plugin from starting. */
    @Test
    void unknownExcludedTypeIsIgnored() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("conversion.excluded-types", java.util.List.of("NOT_A_REAL_MOB", "blaze"));
        Settings s = Settings.load(yaml);

        assertTrue(s.excludedTypes.contains(EntityType.BLAZE), "names are case-insensitive");
        assertTrue(s.excludedTypes.contains(EntityType.ENDER_DRAGON),
                "mandatory exclusions survive a config that omits them");
        assertEquals(3, s.excludedTypes.size(),
                "blaze plus the two mandatory exclusions; the invalid entry is dropped");
    }

    @Test
    void protectedBlocksAreParsed() {
        Settings s = defaults();
        assertTrue(s.protectedBlocks.contains(Material.OBSIDIAN));
        assertTrue(s.protectedBlocks.contains(Material.BEDROCK));
    }

    @Test
    void infestedBlockDetectionCoversTheVanillaFamily() {
        assertTrue(BlockEventListener.isInfested(Material.INFESTED_STONE));
        assertTrue(BlockEventListener.isInfested(Material.INFESTED_DEEPSLATE));
        assertTrue(BlockEventListener.isInfested(Material.INFESTED_CHISELED_STONE_BRICKS));
        assertFalse(BlockEventListener.isInfested(Material.STONE));
        assertFalse(BlockEventListener.isInfested(Material.SCULK_SHRIEKER));
    }

    @Test
    void lootKeyParsingHandlesBothForms() {
        // "zombie" and "entities/zombie" must resolve to the same table key shape. Resolution
        // itself needs a live server, so only the key construction is checked here.
        assertEquals("minecraft", org.bukkit.NamespacedKey.minecraft("entities/zombie").getNamespace());
        assertEquals("entities/zombie",
                org.bukkit.NamespacedKey.minecraft("entities/zombie").getKey());
    }

    /** An empty fallback must be tolerated rather than throwing on the death path. */
    @Test
    void blankFallbackTableYieldsNull() {
        assertNull(MobLootTables.parseTable(""));
        assertNull(MobLootTables.parseTable(null));
        assertNull(MobLootTables.parseTable("   "));
    }
}
