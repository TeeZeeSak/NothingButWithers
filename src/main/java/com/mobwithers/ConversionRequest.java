package com.mobwithers;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

/**
 * Immutable snapshot of everything a conversion needs from the source mob.
 *
 * <p>The snapshot exists because the two halves of a conversion happen at different times. A
 * spawn event has already caused the server to discard the mob that fired it, so the mob's
 * type, name, persistence and position must be read at the moment the event arrives. The
 * replacement itself is then created on the following tick. Passing the entity across that
 * boundary would mean reading a dead object, so the values are copied instead.
 *
 * @param replacementType the type to spawn, always a Wither today
 * @param reason          the spawn reason to record on the replacement
 * @param spawnerBorn     whether a spawner block produced the original
 * @param sourceTypeOverride the spawner's configured mob, or {@code null} to trust the entity
 * @param location        where the source mob stood
 * @param sourceType      the mob type whose loot table the replacement inherits
 * @param lootKey         the loot table key resolved for {@code sourceType}, may be {@code null}
 * @param customName      the source mob's name, may be {@code null}
 * @param persistent      whether the source mob was persistent
 * @param yaw             the source mob's yaw
 * @param pitch           the source mob's pitch
 */
public record ConversionRequest(
        EntityType replacementType,
        SpawnReason reason,
        boolean spawnerBorn,
        EntityType sourceTypeOverride,
        Location location,
        EntityType sourceType,
        NamespacedKey lootKey,
        String customName,
        boolean persistent,
        float yaw,
        float pitch) {

    /**
     * Copies the source mob's state.
     *
     * @return the snapshot, or {@code null} when the mob has no usable location
     */
    public static ConversionRequest capture(Mob original, EntityType replacementType, SpawnReason reason,
                                            boolean spawnerBorn, EntityType sourceTypeOverride) {
        Location location = original.getLocation();
        if (location == null || location.getWorld() == null) {
            return null;
        }
        EntityType sourceType = sourceTypeOverride != null ? sourceTypeOverride : original.getType();
        return new ConversionRequest(
                replacementType,
                reason,
                spawnerBorn,
                sourceTypeOverride,
                location.clone(),
                sourceType,
                MobLootTables.keyFor(sourceType),
                original.getCustomName(),
                original.isPersistent(),
                location.getYaw(),
                location.getPitch());
    }
}
