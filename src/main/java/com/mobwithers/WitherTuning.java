package com.mobwithers;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Wither;

/**
 * Applies the stat block and combat tuning that converted and horde Withers share.
 *
 * <p>Vanilla already gives a Wither 300 health, passive regeneration, a 40 block follow range
 * and decaying block destruction, so this class mostly <em>reasserts</em> those numbers rather
 * than inventing new behaviour. That keeps the horde faithful to a real Wither without needing
 * custom entity code.
 */
public final class WitherTuning {

    /** Vanilla Java Edition Wither health. */
    public static final double WITHER_HEALTH = 300.0D;

    /** Vanilla Wither aggro/detection range in blocks. */
    public static final double AGGRO_RANGE = 40.0D;

    /**
     * Invulnerability ticks applied when the armour phase is engaged manually.
     *
     * <p>Vanilla uses 220 ticks (11 seconds) for the spawning phase. Here a much shorter
     * window is used, purely to flip the projectile-immunity flag; the player should not be
     * handed eleven free seconds every time a Wither crosses half health.
     */
    private static final int ARMOUR_PHASE_TICKS = 20;

    private WitherTuning() {
    }

    /**
     * Applies health, range and speed to a freshly spawned Wither.
     *
     * <p>Values are re-derived from vanilla defaults on every call, so a reload automatically
     * corrects any Wither created before a setting changed.
     */
    public static void tune(Wither wither, Settings settings) {
        setBase(wither, Attribute.MAX_HEALTH, WITHER_HEALTH);
        setBase(wither, Attribute.FOLLOW_RANGE, AGGRO_RANGE);
        setBase(wither, Attribute.MOVEMENT_SPEED, vanillaSpeed(wither, settings.combatSpeedMultiplier));
        // Damage the entity may have inherited from the mob it replaced is discarded, and the
        // full health bar is restored so every converted Wither starts the fight at 300.
        if (wither.getHealth() > 0 && wither.getHealth() < WITHER_HEALTH) {
            wither.setHealth(WITHER_HEALTH);
        }
    }

    private static double vanillaSpeed(Wither wither, double multiplier) {
        AttributeInstance speed = wither.getAttribute(Attribute.MOVEMENT_SPEED);
        double base = speed == null ? 0.25D : speed.getBaseValue();
        return base * multiplier;
    }

    private static void setBase(Wither wither, Attribute attribute, double value) {
        AttributeInstance instance = wither.getAttribute(attribute);
        if (instance != null && instance.getBaseValue() != value) {
            instance.setBaseValue(value);
        }
    }

    /**
     * Engages the armour phase.
     *
     * <p>Below 50% health a Wither becomes immune to arrows and must be finished in melee.
     * The phase is engaged by setting the invulnerability tick counter directly rather than
     * calling {@link Wither#enterInvulnerabilityPhase()}, because that method also <em>heals
     * the Wither to one third of its maximum health</em>. Calling it here would undo the
     * damage that triggered the transition, so the player could never actually finish the
     * fight.
     *
     * @return true when the phase was newly engaged by this call
     */
    public static boolean applyArmourPhase(Wither wither) {
        if (wither.getInvulnerableTicks() > 0) {
            return false;
        }
        wither.setInvulnerableTicks(ARMOUR_PHASE_TICKS);
        return true;
    }

    /** True when the Wither is currently in its projectile-immune armour phase. */
    public static boolean inArmourPhase(Wither wither) {
        return wither.getInvulnerableTicks() > 0;
    }
}
