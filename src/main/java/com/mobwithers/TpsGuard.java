package com.mobwithers;

import org.bukkit.Bukkit;

/**
 * Cheap, allocation-free view of server health used to shed load before TPS collapses.
 *
 * <p>Hundreds of flying Withers pathfinding inside a Woodland Mansion will drive TPS down
 * long before the server reports a problem. Two behaviours are gated on this guard:
 * conversion is paused so the horde cannot grow while the server is struggling, and terrain
 * destruction is suspended so block updates stop competing with entity ticking.
 *
 * <p>{@link Bukkit#getTPS()} is a one second rolling average and is accurate enough for a
 * hysteretic decision. The result is cached per tick because several listeners consult it
 * inside the same tick.
 */
public final class TpsGuard {

    private volatile double cachedTps = 20.0D;
    private volatile int cachedTick = -1;

    /** TPS below which the server is considered to be struggling. */
    private static final double HEALTHY_TPS = 19.0D;

    /**
     * Current TPS, cached for the duration of one server tick.
     */
    public double tps() {
        int tick = Bukkit.getCurrentTick();
        if (tick != cachedTick) {
            double[] samples = Bukkit.getTPS();
            cachedTps = samples.length > 0 ? samples[0] : 20.0D;
            cachedTick = tick;
        }
        return cachedTps;
    }

    /** True when conversions may proceed. */
    public boolean allowConversion(Settings settings) {
        return tps() >= settings.minTpsToConvert;
    }

    /** True when Wither block destruction may proceed. */
    public boolean allowTerrainDamage(Settings settings) {
        if (!settings.terrainEnabled) {
            return false;
        }
        return tps() >= settings.terrainDisableBelowTps;
    }

    /** True when the server is under enough load that new work should be refused. */
    public boolean underStress() {
        return tps() < HEALTHY_TPS;
    }

    /** Human readable load description for the admin command. */
    public String describe() {
        double tps = tps();
        String band = tps >= HEALTHY_TPS ? "healthy" : tps >= 12.0D ? "degraded" : "critical";
        return "%.1f TPS (%s)".formatted(tps, band);
    }
}
