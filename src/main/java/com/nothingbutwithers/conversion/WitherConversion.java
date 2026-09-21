package com.nothingbutwithers.conversion;

import com.nothingbutwithers.NothingButWithers;
import com.nothingbutwithers.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.Set;

/** Central decision point: should this entity be turned into a Wither, and if so how. */
public final class WitherConversion {
	private WitherConversion() {
	}

	private static final ResourceKey<LootTable> FALLBACK_WITHER_LOOT =
			ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("entities/wither"));

	/**
	 * Entity types that are never converted.
	 *
	 * <p>Water creatures are excluded deliberately. Vanilla keeps them under separate spawn caps
	 * ({@code WATER_CREATURE} / {@code WATER_AMBIENT}), and a converted Wither leaves those caps
	 * permanently unfilled. When a cap stays below its limit the natural spawner keeps trying to
	 * fill it, which becomes an unbounded spawn loop. Leaving these mobs alone keeps their caps
	 * satisfied by real fish and squid.
	 *
	 * <p>The Wither itself is excluded so a player-built Wither is never touched. This is the only
	 * place that guarantee is enforced.
	 */
	private static final Set<EntityType<?>> NEVER_CONVERT = Set.of(
			EntityTypes.ENDER_DRAGON,
			EntityTypes.SQUID,
			EntityTypes.GLOW_SQUID,
			EntityTypes.COD,
			EntityTypes.SALMON,
			EntityTypes.TROPICAL_FISH,
			EntityTypes.PUFFERFISH,
			EntityTypes.DOLPHIN,
			EntityTypes.AXOLOTL,
			EntityTypes.TADPOLE,
			EntityTypes.WITHER);

	/** Cheap pre-filter applied to every entity the world tries to add. */
	public static boolean isConvertible(Entity entity, ModConfig config) {
		if (!config.enabled || !(entity instanceof Mob mob)) {
			return false;
		}
		if (!(entity.level() instanceof ServerLevel level)) {
			return false;
		}

		EntityType<?> type = mob.getType();
		if (NEVER_CONVERT.contains(type)) {
			return false;
		}
		if (config.excludedEntities.contains(EntityType.getKey(type).toString())) {
			return false;
		}

		// On Peaceful the world should stay quiet; converting would spawn hostile bosses.
		return level.getDifficulty() != Difficulty.PEACEFUL;
	}

	/**
	 * Replaces {@code mob} with a Wither and returns the new Wither, or {@code null} if the
	 * replacement was refused.
	 *
	 * <p>A {@code null} return means <em>the original mob was left untouched</em>, not discarded. The
	 * caller must therefore leave the original mob in the world rather than refusing it. This matters
	 * most for the placement check: a zombie spawning inside a cramped structure cannot host a Wither,
	 * and should simply remain a zombie.
	 *
	 * <p>{@code convertTo} adds the new entity to the level immediately and discards the original, so
	 * the placement check is performed on the entity type's spawn box <em>before</em> converting. Doing
	 * it afterwards would necessarily discard the original mob, because by then it is already gone.
	 */
	public static WitherBoss convert(Mob mob, ModConfig config) {
		if (!(mob.level() instanceof ServerLevel level)) {
			return null;
		}
		BlockPos pos = mob.blockPosition();
		if (!respectsCaps(level, pos, config)) {
			debug("{} refused at {}: cap reached (chunk={} total={})",
					EntityType.getKey(mob.getType()), pos,
					WitherRegistry.countInChunk(level, pos.getX() >> 4, pos.getZ() >> 4),
					WitherRegistry.count(level));
			return null;
		}

		// Reject before converting if a Wither would not fit here, so the original mob survives.
		if (!level.noCollision(mob.getType().getSpawnAABB(mob.getX(), mob.getY(), mob.getZ()))) {
			debug("{} at {}: no room for a replacement", EntityType.getKey(mob.getType()), pos);
			return null;
		}

		Identifier originalId = EntityType.getKey(mob.getType());
		// Capture the loot table from the live mob. This is authoritative for modded entities, whose
		// table name is not derivable from the entity id.
		String lootTable = mob.getLootTable()
				.map(key -> key.identifier().toString())
				.orElse(null);

		WitherBoss wither = mob.convertTo(
				EntityTypes.WITHER,
				ConversionParams.single(mob, false, true),
				EntitySpawnReason.CONVERSION,
				converted -> {
					if (lootTable != null) {
						converted.setAttached(ConvertedWitherData.LOOT_TABLE, lootTable);
					}
					converted.setAttached(ConvertedWitherData.ORIGINAL_ENTITY, originalId.toString());
					// Converted Withers must persist: they may not despawn, unlike the Warden they fill
					// in for at a Sculk Shrieker.
					converted.setPersistenceRequired();
					converted.setHealth(converted.getMaxHealth());
				});

		if (wither == null || wither.isRemoved()) {
			debug("{} at {}: convertTo produced no entity", originalId, pos);
			return null;
		}

		WitherRegistry.invalidate();
		debug("{} at {} -> wither", originalId, pos);
		return wither;
	}

	/**
	 * Enforces the per-chunk and global Wither budgets so a large loaded area cannot accumulate an
	 * unbounded number of flying, terrain-destroying bosses.
	 */
	private static boolean respectsCaps(ServerLevel level, BlockPos pos, ModConfig config) {
		if (config.maxConvertedWithersTotal > 0
				&& WitherRegistry.count(level) >= config.maxConvertedWithersTotal) {
			return false;
		}
		if (config.maxConvertedWithersPerChunk > 0) {
			if (WitherRegistry.countInChunk(level, pos.getX() >> 4, pos.getZ() >> 4)
					>= config.maxConvertedWithersPerChunk) {
				return false;
			}
		}
		return true;
	}

	/** Loot table a converted Wither should roll, falling back to the vanilla Wither table. */
	public static ResourceKey<LootTable> lootTableFor(WitherBoss wither) {
		String stored = wither.getAttached(ConvertedWitherData.LOOT_TABLE);
		if (stored != null) {
			Identifier id = Identifier.tryParse(stored);
			if (id != null) {
				return ResourceKey.create(Registries.LOOT_TABLE, id);
			}
			NothingButWithers.LOGGER.warn("Wither held an unparseable loot table '{}'", stored);
		}
		return FALLBACK_WITHER_LOOT;
	}

	/**
	 * Rolls the source mob's loot table for a converted Wither.
	 *
	 * <p>Delegates to the vanilla routine so the loot context is built exactly as it would be for
	 * any other mob, which is what lets the Looting enchantment apply.
	 */
	public static void rollSourceLoot(WitherBoss wither, ServerLevel level,
				net.minecraft.world.damagesource.DamageSource source, boolean hitByPlayer) {
		ResourceKey<LootTable> table = lootTableFor(wither);
		debug("rolling {} for converted wither", table.identifier());
		wither.dropFromLootTable(level, source, hitByPlayer, table);
	}

	private static void debug(String message, Object... args) {
		if (Boolean.getBoolean("nothingbutwithers.debug")) {
			NothingButWithers.LOGGER.info("[debug] " + message, args);
		}
	}
}
