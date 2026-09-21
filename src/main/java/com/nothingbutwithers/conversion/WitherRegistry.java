package com.nothingbutwithers.conversion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks how many converted Withers are loaded so spawning can be kept bounded.
 *
 * <p>Counts are derived from the level's live entity list. A single scan produces both the global
 * total and the per-chunk breakdown, and the result is reused by every spawn attempt in the same
 * tick so a burst of spawns costs one scan rather than one scan each.
 *
 * <p>The cache is deliberately not tied to a tick event. An idle server stops ticking entirely
 * (vanilla pauses after 60 seconds with no players), which would leave a tick-driven cache
 * populated with stale counts and permanently block spawning in the affected chunks.
 */
public final class WitherRegistry {
	private WitherRegistry() {
	}


	/** World borders cannot exceed this, so the box covers any world that can exist. */
	private static final AABB EVERYTHING =
			new AABB(-3.0E7D, -3.0E7D, -3.0E7D, 3.0E7D, 3.0E7D, 3.0E7D);

	private static final long CACHE_NANOS = 50_000_000L;

	private static int cachedTotal = -1;
	private static long cachedAt = Long.MIN_VALUE;
	private static final Map<Long, Integer> CACHED_PER_CHUNK = new HashMap<>();

	/** Drops the cached counts. Called at the end of every server tick. */
	public static void onTick(int tickCount) {
		invalidate();
	}

	public static void invalidate() {
		cachedTotal = -1;
		cachedAt = Long.MIN_VALUE;
		CACHED_PER_CHUNK.clear();
	}

	public static int count(ServerLevel level) {
		ensureScanned(level);
		return cachedTotal;
	}

	public static int countInChunk(ServerLevel level, int chunkX, int chunkZ) {
		ensureScanned(level);
		Integer cached = CACHED_PER_CHUNK.get(ChunkPos.pack(chunkX, chunkZ));
		return cached == null ? 0 : cached;
	}

	/**
	 * Scans the level once per cache window and records the global total plus a count for every
	 * chunk that actually holds a converted Wither, so the per-chunk map stays bounded by the number
	 * of loaded chunks rather than by how many spawn attempts occur.
	 */
	private static void ensureScanned(ServerLevel level) {
		long now = System.nanoTime();
		if (cachedTotal >= 0 && now - cachedAt < CACHE_NANOS) {
			return;
		}

		List<WitherBoss> withers = level.getEntities(EntityTypeTest.forClass(WitherBoss.class), EVERYTHING,
				WitherRegistry::isConverted);

		CACHED_PER_CHUNK.clear();
		for (WitherBoss wither : withers) {
			ChunkPos pos = wither.chunkPosition();
			CACHED_PER_CHUNK.merge(ChunkPos.pack(pos.x(), pos.z()), 1, Integer::sum);
		}
		cachedTotal = withers.size();
		cachedAt = now;
	}

	/** True when this entity is a Wither produced by this mod. */
	public static boolean isConverted(Entity entity) {
		return entity.getType() == EntityTypes.WITHER
				&& entity.hasAttached(ConvertedWitherData.ORIGINAL_ENTITY);
	}
}
