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
 * <p>Counts are per {@link ServerLevel}. Dimensions share nothing: a Nether chunk at (0,0) and an
 * Overworld chunk at (0,0) are different chunks, and one global count would let one dimension's
 * Withers consume another dimension's budget.
 *
 * <p>The count is derived from the level's loaded entity list. The pass must not be done through an
 * AABB overload: {@code Level#getEntities(EntityTypeTest, AABB, Predicate)} walks every entity
 * section coordinate inside the box, so a world-sized box is millions of coordinates and stalls the
 * server for tens of milliseconds per call. The AABB-free overload iterates loaded entities
 * directly, so cost scales with entity count instead of world size.
 *
 * <p>There is deliberately no cached total. Caps are hard limits, and a cached count lets a burst of
 * spawn attempts all observe the same pre-burst value and overshoot the cap. Only the per-chunk
 * breakdown is cached, and the cache is invalidated by every conversion, so a conversion can never
 * be permitted on the strength of a count that predates it.
 */
public final class WitherRegistry {
	private WitherRegistry() {
	}

	/** How long a scan result is reused when nothing invalidates it first. */
	private static final long CACHE_NANOS = 10_000_000L;

	private static final EntityTypeTest<Entity, WitherBoss> CONVERTED_TYPE =
			EntityTypeTest.forClass(WitherBoss.class);

	/** Cache for the most recently scanned level; discarded the moment a different level is queried. */
	private static ServerLevel cachedLevel;
	private static int cachedTotal = -1;
	private static long cachedAt = Long.MIN_VALUE;
	private static final Map<Long, Integer> CACHED_PER_CHUNK = new HashMap<>();

	/**
	 * True when this entity is a Wither produced by this mod.
	 *
	 * <p>A Wither a player built carries none of the conversion markers, so it is never counted
	 * against a cap and is never itself converted.
	 */
	public static boolean isConverted(Entity entity) {
		return entity.getType() == EntityTypes.WITHER
				&& entity.hasAttached(ConvertedWitherData.LOOT_TABLE);
	}

	/** Drops the cached counts. Called whenever a conversion changes the population. */
	public static void invalidate() {
		cachedLevel = null;
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
	 * Passes once over the loaded entities and records the level's global total plus a count for every
	 * chunk that actually holds a converted Wither, so the per-chunk map stays bounded by the number
	 * of loaded chunks rather than by how many spawn attempts occur.
	 */
	private static void ensureScanned(ServerLevel level) {
		long now = System.nanoTime();
		if (cachedLevel == level && cachedTotal >= 0 && now - cachedAt < CACHE_NANOS) {
			return;
		}

		List<? extends WitherBoss> withers = level.getEntities(CONVERTED_TYPE, WitherRegistry::isConverted);

		CACHED_PER_CHUNK.clear();
		for (WitherBoss wither : withers) {
			ChunkPos pos = wither.chunkPosition();
			CACHED_PER_CHUNK.merge(ChunkPos.pack(pos.x(), pos.z()), 1, Integer::sum);
		}
		cachedLevel = level;
		cachedTotal = withers.size();
		cachedAt = now;
	}
}
