package com.nothingbutwithers.spawn;

import com.nothingbutwithers.NothingButWithers;
import com.nothingbutwithers.config.ModConfig;
import com.nothingbutwithers.conversion.WitherConversion;
import com.nothingbutwithers.conversion.WitherRegistry;
import net.minecraft.world.entity.EntityTypes;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

/**
 * Universal spawn interception.
 *
 * <p>Fabric's {@code ALLOW_LOAD} callback fires from {@code PersistentEntitySectionManager}, the
 * single point at which any server-side entity is registered. That already covers natural
 * spawning, structure and chunk-generation spawning, monster and trial spawners, and block event
 * spawns such as infested stone, so no extra join-level hook is needed.
 *
 * <p>Returning {@code false} refuses the original entity outright rather than letting it in
 * alongside a Wither, so no stray mobs are left in the world.
 */
public final class SpawnInterceptor {
	private SpawnInterceptor() {
	}

	/** Guards against re-entrancy while the replacement entity is being added. */
	private static final ThreadLocal<Boolean> CONVERTING = ThreadLocal.withInitial(() -> false);

	public static void register() {
		ServerEntityEvents.ALLOW_LOAD.register(SpawnInterceptor::onAllowLoad);
	}

	private static boolean onAllowLoad(Entity entity, ServerLevel level, EntitySpawnReason reason,
			boolean loadedFromDisk) {
		// Entities streamed back in from a save must be left alone, or converted Withers would be
		// re-processed on every chunk load.
		if (loadedFromDisk || Boolean.TRUE.equals(CONVERTING.get())) {
			return true;
		}
		if (reason == EntitySpawnReason.CONVERSION) {
			return true;
		}

		ModConfig config = ModConfig.get();
		if (!(entity instanceof Mob mob) || !WitherConversion.isConvertible(mob, config)) {
			return true;
		}

		// A Wither a player built keeps vanilla behaviour and loot, including the Nether Star.
		if (config.preservePlayerBuiltWithers && mob.getType() == EntityTypes.WITHER) {
			return true;
		}

		CONVERTING.set(true);
		try {
			WitherConversion.convert(mob, config);
		} catch (Exception e) {
			NothingButWithers.LOGGER.error("Failed to convert {} at {}",
					EntityType.getKey(mob.getType()), mob.blockPosition(), e);
		} finally {
			CONVERTING.set(false);
		}

		// The original mob is refused; either a Wither replaced it or nothing spawned at all.
		return false;
	}

	public static void onTick(int tickCount) {
		WitherRegistry.onTick(tickCount);
	}
}
