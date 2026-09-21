package com.nothingbutwithers.conversion;

import com.mojang.serialization.Codec;
import com.nothingbutwithers.NothingButWithers;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;

/**
 * Marks a Wither as having been produced by this mod's conversion, and records the entity it
 * replaced so the original loot table can be rolled on death.
 *
 * <p>An attachment is used rather than a raw NBT tag because it is type-safe, participates in the
 * normal save/load cycle, and survives a chunk unload and reload.
 */
public final class ConvertedWitherData {
	private ConvertedWitherData() {
	}

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
	 * this class would therefore lose the marker, and the source loot table with it, for every
	 * converted Wither that was saved to disk.
	 */
	public static void register() {
	}
}
