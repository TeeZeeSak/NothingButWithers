package com.nothingbutwithers;

import com.nothingbutwithers.config.ModConfig;
import com.nothingbutwithers.conversion.ConvertedWitherData;
import com.nothingbutwithers.spawn.SpawnInterceptor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entrypoint.
 *
 * <p>Replacing every mob with a Wither is inherently expensive, so the tuning in
 * {@link ModConfig} is applied from the first tick and a summary is written to the log at startup.
 */
public class NothingButWithers implements ModInitializer {
	public static final String MOD_ID = "nothingbutwithers";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Nothing But Withers initializing");

		// Touch the attachment holder so its types register during mod init. Fabric reads persisted
		// attachments off disk as chunks load, and anything registered later is discarded as unknown.
		ConvertedWitherData.register();

		SpawnInterceptor.register();

		ServerTickEvents.END_SERVER_TICK.register(server -> SpawnInterceptor.onTick(server.getTickCount()));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			ModConfig config = ModConfig.get();
			if (!config.announceOnStart) {
				return;
			}
			if (!config.enabled) {
				LOGGER.info("Conversion is disabled in the config; vanilla spawning applies.");
				return;
			}
			LOGGER.info("Every eligible mob will spawn as a Wither. Caps: {} per chunk, {} total.",
					config.maxConvertedWithersPerChunk, config.maxConvertedWithersTotal);
			for (String warning : config.warnings()) {
				LOGGER.warn("{}", warning);
			}
		});
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
