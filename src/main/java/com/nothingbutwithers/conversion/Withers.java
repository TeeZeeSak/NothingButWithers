package com.nothingbutwithers.conversion;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Helpers for identifying converted Withers and the loot they should drop.
 */
public final class Withers {
	private Withers() {
	}

	/** Loot table used by Withers that were not built by a player and have no stored source. */
	public static final Identifier VANILLA_WITHER_LOOT = Identifier.withDefaultNamespace("entities/wither");

	/**
	 * Vanilla derives an entity's loot table as {@code <namespace>:entities/<path>}. Every vanilla
	 * entity id happens to follow that convention, so the original mob's loot table can be
	 * reconstructed from its entity id alone.
	 */
	public static ResourceKey<LootTable> fromEntityId(Identifier entityId) {
		return ResourceKey.create(Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath(
				entityId.getNamespace(), "entities/" + entityId.getPath()));
	}
}
