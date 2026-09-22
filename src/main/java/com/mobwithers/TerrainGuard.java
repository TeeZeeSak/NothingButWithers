package com.mobwithers;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Wither;

/**
 * Clamps Wither terrain erosion so the horde cannot chew the world apart at TPS cost.
 *
 * <p>This is a deliberate trade-off. An unmodified Wither destroys terrain, and that is a
 * large part of what makes the mob terrifying, so the goal is not to disable it but to bound
 * its cost. Three bounds are applied:
 *
 * <ul>
 *   <li>Blast size is capped at a configurable block count.</li>
 *   <li>Protected blocks are removed from the break list.</li>
 *   <li>All breakage is suspended while TPS is low, or while the server is shutting down.</li>
 * </ul>
 *
 * <p>Because {@code EntityExplodeEvent#blockList()} is the authoritative list of blocks the
 * engine is about to remove, mutating it is sufficient: no re-implementation of explosion
 * physics is needed, and the visual and audio effects are untouched.
 */
public final class TerrainGuard {

    private final MobWithersPlugin plugin;

    public TerrainGuard(MobWithersPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Applies the blast clamp to an explosion caused by a Wither.
     *
     * @param source the exploding entity, expected to be a Wither
     * @param blocks the mutable list from the explode event
     * @return the number of blocks removed from the list
     */
    public int clamp(Entity source, java.util.List<Block> blocks) {
        Settings s = plugin.settings();

        if (!plugin.tpsGuard().allowTerrainDamage(s)) {
            int removed = blocks.size();
            blocks.clear();
            return removed;
        }

        int removed = 0;
        // Protected blocks are dropped regardless of the blast size budget.
        if (!s.protectedBlocks.isEmpty()) {
            removed += dropProtected(s, blocks);
        }

        int budget = s.maxBlocksPerExplosion;
        if (blocks.size() > budget) {
            // Randomly thin the list rather than truncating it, so erosion does not always
            // favour one direction of the blast and carve tunnels unnaturally.
            shuffle(blocks);
            removed += blocks.size() - budget;
            blocks.subList(budget, blocks.size()).clear();
        }
        return removed;
    }

    private static int dropProtected(Settings s, java.util.List<Block> blocks) {
        int before = blocks.size();
        blocks.removeIf(block -> s.protectedBlocks.contains(block.getType()));
        return before - blocks.size();
    }

    /**
     * Optional erosion: nudges a surface Wither into the ground over time.
     *
     * <p>The requirement calls for surface Withers to blast their way down toward caves and
     * Ancient Cities. Rather than teleporting them, this simply applies a small downward
     * impulse when the Wither has been unable to find a target on the surface, letting the
     * normal flight and charge behaviour carve the hole.
     */
    public void applyErosionNudge(Wither wither) {
        Settings s = plugin.settings();
        if (!s.terrainEnabled || !plugin.tpsGuard().allowTerrainDamage(s)) {
            return;
        }
        if (wither.getTarget() != null) {
            return;
        }
        Location location = wither.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        // Only nudge when there is solid ground close below, so a Wither in open sky or
        // over the void does not fight its own levitation.
        Block below = location.clone().subtract(0, 1, 0).getBlock();
        if (!below.getType().isSolid()) {
            return;
        }
        wither.setVelocity(wither.getVelocity().add(new org.bukkit.util.Vector(0, -0.04D, 0)));
    }

    /** True when the given block is on the protected list. */
    public boolean isProtected(Block block) {
        return plugin.settings().protectedBlocks.contains(block.getType());
    }

    /** Human readable summary of the active clamp for the admin command. */
    public String describe() {
        Settings s = plugin.settings();
        return "enabled=%s, maxBlocks=%d, disableBelow=%s TPS, protected=%d"
                .formatted(s.terrainEnabled, s.maxBlocksPerExplosion, s.terrainDisableBelowTps,
                        s.protectedBlocks.size());
    }

    /** Fisher-Yates shuffle so the surviving blocks are spread across the blast sphere. */
    private static void shuffle(java.util.List<Block> blocks) {
        java.util.Random random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = blocks.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Block tmp = blocks.get(i);
            blocks.set(i, blocks.get(j));
            blocks.set(j, tmp);
        }
    }
}
