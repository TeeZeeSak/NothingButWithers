package com.mobwithers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.World;
import org.bukkit.entity.Wither;

/**
 * Lightweight counters backing the {@code /mobwithers status} output.
 *
 * <p>All counters are monotonic since the last reload, except the live Wither tallies which
 * are recomputed by {@link #refreshLiveCounts()}.
 */
public final class Statistics {

    private final AtomicLong converted = new AtomicLong();
    private final AtomicLong refusedByWorldCap = new AtomicLong();
    private final AtomicLong refusedByChunkCap = new AtomicLong();
    private final AtomicLong refusedByTps = new AtomicLong();
    private final AtomicLong terrainBlocksSkipped = new AtomicLong();
    private final AtomicLong infestedBreaks = new AtomicLong();
    private final AtomicLong hordeSummons = new AtomicLong();
    private final AtomicLong frozenAi = new AtomicLong();

    private final Map<String, Integer> livePerWorld = new ConcurrentHashMap<>();

    /** Records one successful conversion. */
    public void converted() {
        converted.incrementAndGet();
    }

    /** Records one conversion refused by a cap. */
    public void refused(String kind) {
        switch (kind) {
            case "world" -> refusedByWorldCap.incrementAndGet();
            case "chunk" -> refusedByChunkCap.incrementAndGet();
            case "tps" -> refusedByTps.incrementAndGet();
            default -> {
            }
        }
    }

    /** Records blocks the terrain clamp prevented from being destroyed. */
    public void terrainSkipped(int blocks) {
        terrainBlocksSkipped.addAndGet(blocks);
    }

    /** Records an infested block break. */
    public void infestedBreak() {
        infestedBreaks.incrementAndGet();
    }

    /** Records a sculk-shrieker horde summon. */
    public void hordeSummon() {
        hordeSummons.incrementAndGet();
    }

    /** Records that a Wither's AI was frozen or restored this pass. */
    public void frozenAi(long delta) {
        frozenAi.addAndGet(delta);
    }

    /** Recomputes the per-world live Wither tallies. */
    public void refreshLiveCounts() {
        livePerWorld.clear();
        for (World world : org.bukkit.Bukkit.getWorlds()) {
            int count = 0;
            for (Wither wither : world.getEntitiesByClass(Wither.class)) {
                count++;
            }
            livePerWorld.put(world.getName(), count);
        }
    }

    /** Total converted since the last reset. */
    public long convertedTotal() {
        return converted.get();
    }

    /** Number of loaded Withers in a world. */
    public int liveIn(String worldName) {
        return livePerWorld.getOrDefault(worldName, 0);
    }

    /** Sum of loaded Withers across every world. */
    public int liveTotal() {
        int sum = 0;
        for (int value : livePerWorld.values()) {
            sum += value;
        }
        return sum;
    }

    /** Multi-line summary for the admin command. */
    public String report() {
        return """
                converted=%d  refused(world=%d chunk=%d tps=%d)
                terrainBlocksSkipped=%d  infestedBreaks=%d  hordeSummons=%d  frozenAiActions=%d
                liveWithers=%d%s"""
                .formatted(converted.get(), refusedByWorldCap.get(), refusedByChunkCap.get(),
                        refusedByTps.get(), terrainBlocksSkipped.get(), infestedBreaks.get(),
                        hordeSummons.get(), frozenAi.get(), liveTotal(), livePerWorld.isEmpty() ? ""
                                : " " + livePerWorld);
    }

    /** Zeroes every counter. */
    public void reset() {
        converted.set(0);
        refusedByWorldCap.set(0);
        refusedByChunkCap.set(0);
        refusedByTps.set(0);
        terrainBlocksSkipped.set(0);
        infestedBreaks.set(0);
        hordeSummons.set(0);
        frozenAi.set(0);
    }
}
