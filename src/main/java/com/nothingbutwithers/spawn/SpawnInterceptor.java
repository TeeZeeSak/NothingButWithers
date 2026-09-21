package com.nothingbutwithers.spawn;

import com.nothingbutwithers.config.ModConfig;
import com.nothingbutwithers.conversion.WitherConversion;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;

/**
 * Universal spawn interception.
 *
 * <p>Fabric's {@code ALLOW_LOAD} callback fires from {@code PersistentEntitySectionManager}, the
 * single point at which any server-side entity is registered. That already covers natural spawning,
 * structure and chunk-generation spawning, monster and trial spawners, and block event spawns such
 * as infested stone, so no extra join-level hook is needed. There is no distinction between spawn
 * reasons here: command summons, scripted spawns and natural spawns all pass through, which matches
 * the goal of replacing every mob that enters the world.
 *
 * <p>A successful conversion refuses the original entity, which has already been replaced by the
 * Wither, so no stray mobs are left behind. A declined conversion leaves the original entity alone:
 * it is the only mob present, and refusing it would delete the mob outright. That distinction
 * matters when a replacement does not fit, for instance a zombie inside a cramped structure. It
 * should stay a zombie.
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

		CONVERTING.set(true);
		try {
			if (WitherConversion.convert(mob, config) == null) {
				// No replacement was made, so the original mob is all there is. Let it load.
				return true;
			}
		} finally {
			CONVERTING.set(false);
		}

		// The Wither replaced the mob; refuse the original so only one entity remains.
		return false;
	}
}
