package com.mobwithers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;

import net.kyori.adventure.bossbar.BossBar.Color;
import net.kyori.adventure.bossbar.BossBar.Overlay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Keeps the player's screen clear when dozens of Withers are loaded at once.
 *
 * <p>Every vanilla Wither pushes its own boss bar. That is fine for a single Wither, but with
 * a horde loaded the bars stack down the side of the screen until nothing else is readable.
 * Two strategies are available, chosen by {@code boss-bar.aggregate}:
 *
 * <ul>
 *   <li><b>hide</b> - each Wither bar is removed from every player's view, repeatedly.</li>
 *   <li><b>aggregate</b> - the individual bars are still hidden, and one summary bar reports
 *       how many Withers are near each player.</li>
 * </ul>
 *
 * <h2>Why {@code removePlayer} rather than a name-based lookup</h2>
 * A Wither's boss bar is created and owned by the engine, and {@link org.bukkit.entity.Boss#getBossBar()}
 * returns that live instance. The bar is not registered in {@link Bukkit#getBossBars()}, so
 * the reliable handle is the returned object itself, and {@link BossBar#removePlayer} is the
 * supported way to take one player off it. Because the engine re-adds nearby players, the
 * removal is re-applied on a timer rather than once.
 *
 * <p>The concentrated bar-per-Wither cost is only paid while a sweep is running, and the
 * sweep interval is configurable, so this scales from "one Wither" to "a hundred Withers".
 */
public final class BossBarManager {

    private final MobWithersPlugin plugin;

    /** One aggregate bar per world, created lazily and reused between sweeps. */
    private final Map<UUID, net.kyori.adventure.bossbar.BossBar> aggregateBars = new ConcurrentHashMap<>();

    /** Last aggregate count pushed to each player, so unchanged updates are skipped. */
    private final Map<UUID, Integer> lastShown = new ConcurrentHashMap<>();

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private int taskId = -1;

    public BossBarManager(MobWithersPlugin plugin) {
        this.plugin = plugin;
    }

    /** Starts the suppression sweep. */
    public void start() {
        stop();
        Settings s = plugin.settings();
        if (!s.bossBarEnabled) {
            return;
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::sweep,
                s.bossBarIntervalTicks, s.bossBarIntervalTicks);
    }

    /** Cancels the sweep and removes the aggregate bars. */
    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Player player : world.getPlayers()) {
                clearAggregate(world, player);
            }
        }
        aggregateBars.clear();
        lastShown.clear();
    }

    /**
     * Hides a newly created Wither's bar right away, without waiting for the next sweep.
     *
     * <p>The periodic sweep alone leaves a visible gap: the engine adds the bar the instant the
     * Wither ticks, so a horde created between two sweeps briefly stacks dozens of bars on the
     * player's screen. This runs a short burst of removals over the first second of the
     * Wither's life, and the sweep takes over after that. The burst is bounded and targets a
     * single entity, so it costs far less than simply sweeping more often.
     */
    public void suppressSoon(Wither wither) {
        Settings s = plugin.settings();
        if (!s.enabled || !s.bossBarEnabled) {
            return;
        }
        for (long delay : SUPPRESS_BURST_TICKS) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> silenceWither(wither), delay);
        }
    }

    private void silenceWither(Wither wither) {
        if (!wither.isValid()) {
            return;
        }
        try {
            BossBar bar = wither.getBossBar();
            if (bar != null && bar.isVisible()) {
                bar.setVisible(false);
            }
        } catch (Throwable unavailable) {
            // A Wither that died mid-burst is not worth logging.
        }
    }

    /** Ticks after creation at which the bar is stripped again; the engine re-adds it each tick. */
    private static final long[] SUPPRESS_BURST_TICKS = {1L, 2L, 4L, 8L, 20L};

    /** One suppression pass over every loaded world. */
    private void sweep() {
        Settings s = plugin.settings();
        if (!s.enabled || !s.bossBarEnabled) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            hideWitherBars(world);
            if (s.bossBarAggregate) {
                updateAggregate(world, s);
            }
        }
    }

    /**
     * Silences each loaded Wither's own boss bar.
     *
     * <p>Removing players one at a time is not stable: the engine re-adds every tracked player
     * to a boss entity's bar on each entity update, so a removal is undone within a couple of
     * ticks and the bars are visible almost all the time. Hiding the bar itself is the stable
     * fix, because the engine flips visibility to true exactly once, when the Wither is
     * constructed, and never touches it again. After this call the bar stays invisible for the
     * Wither's whole life, so the work is a single pass per Wither rather than per player.
     */
    private void hideWitherBars(World world) {
        Collection<Wither> withers = world.getEntitiesByClass(Wither.class);
        for (Wither wither : withers) {
            if (!wither.isValid()) {
                continue;
            }
            try {
                BossBar bar = wither.getBossBar();
                if (bar != null && bar.isVisible()) {
                    bar.setVisible(false);
                }
            } catch (Throwable unavailable) {
                // A Wither that died mid-sweep is not worth logging.
            }
        }
    }

    /**
     * Counts how many (Wither, player) pairs still show a bar.
     *
     * <p>Used by the admin command to prove suppression is actually working. The engine
     * re-adds nearby players to a Wither's bar whenever it ticks, so the meaningful check is
     * that this reaches zero shortly after each sweep rather than that a single
     * {@code removePlayer} call was made.
     */
    public int visibleViewerPairs() {
        int pairs = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Wither wither : world.getEntitiesByClass(Wither.class)) {
                BossBar bar;
                try {
                    bar = wither.getBossBar();
                } catch (Throwable unavailable) {
                    continue;
                }
                if (bar != null && bar.isVisible()) {
                    pairs += bar.getPlayers().size();
                }
            }
        }
        return pairs;
    }

    /** Replaces the per-Wither bars with a single count bar per player. */
    private void updateAggregate(World world, Settings s) {
        List<Wither> withers = new ArrayList<>(world.getEntitiesByClass(Wither.class));
        for (Player player : world.getPlayers()) {
            int nearby = 0;
            for (Wither wither : withers) {
                if (wither.getLocation().distanceSquared(player.getLocation()) <= 128 * 128) {
                    nearby++;
                }
            }
            int shown = nearby < s.bossBarAggregateMinimum ? 0 : nearby;
            Integer previous = lastShown.get(player.getUniqueId());
            if (previous != null && previous == shown) {
                continue;
            }
            lastShown.put(player.getUniqueId(), shown);
            if (shown == 0) {
                clearAggregate(world, player);
                continue;
            }
            net.kyori.adventure.bossbar.BossBar bar = aggregateBar(world, s);
            bar.name(title(s.bossBarTitle, shown));
            bar.progress(1.0F);
            player.showBossBar(bar);
        }
    }

    private net.kyori.adventure.bossbar.BossBar aggregateBar(World world, Settings s) {
        return aggregateBars.computeIfAbsent(world.getUID(),
                id -> net.kyori.adventure.bossbar.BossBar.bossBar(title(s.bossBarTitle, 0), 1.0F,
                        Color.PURPLE, Overlay.PROGRESS));
    }

    private void clearAggregate(World world, Player player) {
        net.kyori.adventure.bossbar.BossBar bar = aggregateBars.get(world.getUID());
        if (bar != null) {
            player.hideBossBar(bar);
        }
        lastShown.remove(player.getUniqueId());
    }

    /** Builds the aggregate title, honouring the legacy {@code &} colour codes from the config. */
    private static Component title(String template, int count) {
        String source = template == null ? "&8Horde Withers" : template;
        return LEGACY.deserialize(source + " &7x" + count);
    }

    /** True when the aggregate bar is currently configured on. */
    public boolean aggregating() {
        return plugin.settings().bossBarAggregate;
    }

    /** Drops cached per-player counts, used on reload. */
    public void resetCache() {
        lastShown.clear();
    }

    /** Summary used by the admin command. */
    public String describe() {
        Settings s = plugin.settings();
        return "enabled=%s, aggregate=%s, every %d ticks, %d aggregate bars, %d visible viewer pairs"
                .formatted(s.bossBarEnabled, s.bossBarAggregate, s.bossBarIntervalTicks,
                        aggregateBars.size(), visibleViewerPairs());
    }
}
