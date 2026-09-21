package com.nothingbutwithers.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.nothingbutwithers.NothingButWithers;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tunable knobs for the conversion, loaded from {@code config/nothingbutwithers.json}.
 *
 * <p>The performance limits default to values that keep the world playable. A Wither is a flying,
 * terrain-destroying boss with 300 health, so leaving every knob open will tank the tick rate.
 */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH =
			FabricLoader.getInstance().getConfigDir().resolve(NothingButWithers.MOD_ID + ".json");

	private static ModConfig instance;

	/** Master switch. When false the mod does nothing and vanilla spawning applies. */
	public boolean enabled = true;

	/** Let a Wither a player builds keep vanilla behaviour and loot. */
	public boolean preservePlayerBuiltWithers = true;

	/** Entity ids that are never converted. The Ender Dragon stays for game completion. */
	public Set<String> excludedEntities = new HashSet<>(Set.of("minecraft:ender_dragon"));

	// --- performance limits -------------------------------------------------

	/** Cap on converted Withers loaded per chunk. 0 disables the cap. */
	public int maxConvertedWithersPerChunk = 4;

	/** Cap across all loaded chunks. Guards against a runaway spawn loop. */
	public int maxConvertedWithersTotal = 200;

	/** Drop the vanilla Nether Star unless the Wither was built by a player. */
	public boolean suppressNetherStar = true;

	// --- combat / terrain ---------------------------------------------------

	/**
	 * Let destructive (blue) skulls break blocks. Disabled by default: with the number of Withers
	 * this mod creates, block breaking rapidly craters the world and costs substantial tick time.
	 */
	public boolean witherSkullsDestroyTerrain = false;

	/** Let Withers run their periodic block-removal sweep. Disabled for the same reasons. */
	public boolean withersDestroyBlocks = false;

	/** Print a one-line summary to the log when the server starts. */
	public boolean announceOnStart = true;

	private static ModConfig defaults() {
		return new ModConfig();
	}

	public static ModConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static ModConfig load() {
		if (!Files.exists(PATH)) {
			ModConfig fresh = defaults();
			fresh.save();
			return fresh;
		}

		try {
			ModConfig loaded = GSON.fromJson(Files.readString(PATH), ModConfig.class);
			if (loaded == null) {
				return defaults();
			}
			loaded.normalise();
			return loaded;
		} catch (IOException | JsonSyntaxException e) {
			NothingButWithers.LOGGER.error("Could not read {}, using defaults", PATH, e);
			return defaults();
		}
	}

	/**
	 * Repairs a partially specified config. Gson constructs via the no-arg constructor, so absent
	 * fields keep their defaults, but a supplied list replaces the default wholesale and may be
	 * missing entries the rest of the code relies on.
	 */
	private void normalise() {
		if (excludedEntities == null) {
			excludedEntities = new HashSet<>();
		} else if (!(excludedEntities instanceof HashSet)) {
			excludedEntities = new HashSet<>(excludedEntities);
		}
		maxConvertedWithersPerChunk = Math.max(0, maxConvertedWithersPerChunk);
		maxConvertedWithersTotal = Math.max(0, maxConvertedWithersTotal);
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this));
		} catch (IOException e) {
			NothingButWithers.LOGGER.error("Could not write {}", PATH, e);
		}
	}

	public List<String> warnings() {
		List<String> warnings = new ArrayList<>();
		if (!enabled) {
			warnings.add("enabled is false; nothing will be converted");
		}
		if (maxConvertedWithersTotal > 0 && maxConvertedWithersTotal < 20) {
			warnings.add("maxConvertedWithersTotal is very low (" + maxConvertedWithersTotal
					+ "); most conversions will be refused");
		}
		if (maxConvertedWithersPerChunk > 12) {
			warnings.add("maxConvertedWithersPerChunk is high (" + maxConvertedWithersPerChunk
					+ "); expect heavy terrain damage and low TPS");
		}
		return warnings;
	}
}
