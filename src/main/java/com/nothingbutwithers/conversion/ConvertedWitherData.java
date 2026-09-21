package com.nothingbutwithers.conversion;

import com.mojang.serialization.Codec;
import com.nothingbutwithers.NothingButWithers;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;

/**
 * Marks a Wither as having been produced by this mod's conversion, and records what it replaced so
 * the original loot can be reproduced on death.
 *
 * <p>Attachments are used rather than raw NBT tags because they are type-safe and participate in the
 * normal save/load cycle, surviving a chunk unload and reload.
 */
public final class ConvertedWitherData {
	private ConvertedWitherData() {
	}

	/**
	 * Loot table the converted Wither should roll.
	 *
	 * <p>The table name is stored rather than reconstructed from the entity id. Vanilla happens to
	 * name every entity's table {@code <namespace>:entities/<path>}, but that is a convention, not a
	 * rule: a modded entity may point its loot table anywhere. Capturing the live table keeps modded
	 * loot working.
	 */
	public static final AttachmentType<String> LOOT_TABLE = AttachmentRegistry
			.<String>builder()
			.persistent(Codec.STRING)
			.buildAndRegister(
					Identifier.fromNamespaceAndPath(NothingButWithers.MOD_ID, "loot_table"));

	/** Entity id of the mob this Wither replaced, kept for diagnostics. */
	public static final AttachmentType<String> ORIGINAL_ENTITY = AttachmentRegistry
			.<String>builder()
			.persistent(Codec.STRING)
			.buildAndRegister(
					Identifier.fromNamespaceAndPath(NothingButWithers.MOD_ID, "original_entity"));

	/**
	 * Forces class initialisation so both attachment types register during mod initialisation.
	 *
	 * <p>Fabric deserialises persisted attachments while chunks load at server start, and reports any
	 * type it does not know as "unknown" and drops the value. Relying on the first conversion to load
	 * this class would therefore lose the markers, and the source loot table with them, for every
	 * converted Wither that was saved to disk.
	 */
	public static void register() {
	}
}
