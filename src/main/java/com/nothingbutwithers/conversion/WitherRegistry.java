package com.nothingbutwithers.conversion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks how many converted Withers are loaded so spawning can be kept bounded.
 *
 * <p>Counts are derived from the level's loaded entity list. A single pass produces both the global
 * total and the per-chunk breakdown, and the result is reused by every spawn attempt in the same
 * cache window so a burst of spawns costs one pass rather than one per attempt.
 *
 * <p>The pass must not be done through an AABB overload. {@code Level#getEntities(EntityTypeTest,
 * AABB, Predicate)} walks every entity section coordinate inside the box, and a box large enough to
 * cover a world is millions of coordinates; on the spawn path that stalls the server for tens of
 * milliseconds per spawn burst. The AABB-free overload iterates the loaded entities directly, so
 * cost scales with entity count rather than with the size of the world.
 *
 * <p>The cache is deliberately not tied to a tick event. An idle server stops ticking entirely
 * (vanilla pauses after 60 seconds with no players), which would leave a tick-driven cache
 * populated with stale counts and permanently block spawning in the affected chunks.
 */
public final class WitherRegistry {
	private WitherRegistry() {
	}

	private static final EntityTypeTest<Entity, WitherBoss> CONVERTED_TYPE =
			EntityTypeTest.forClass(WitherBoss.class);

	/** How long a scan result is reused. Long enough to cover a spawn burst, short enough to stay current. */
	private static final long CACHE_NANOS = 10_000_000L;

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
	 * Passes once over the loaded entities per cache window and records the global total plus a count
	 * for every chunk that actually holds a converted Wither, so the per-chunk map stays bounded by
	 * the number of loaded chunks rather than by how many spawn attempts occur.
	 */
	private static void ensureScanned(ServerLevel level) {
		long now = System.nanoTime();
		if (cachedTotal >= 0 && now - cachedAt < CACHE_NANOS) {
			return;
		}

		List<? extends WitherBoss> withers = level.getEntities(CONVERTED_TYPE, WitherRegistry::isConverted);

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
