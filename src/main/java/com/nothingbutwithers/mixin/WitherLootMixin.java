package com.nothingbutwithers.mixin;

import com.nothingbutwithers.config.ModConfig;
import com.nothingbutwithers.conversion.ConvertedWitherData;
import com.nothingbutwithers.conversion.WitherConversion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives a converted Wither the loot table of the mob it replaced, and withholds the Nether Star.
 *
 * <p>Both behaviours are applied in {@code dropCustomDeathLoot} because it runs immediately after
 * {@code LivingEntity.dropAllDeathLoot} has already rolled the entity's loot table. A Wither's own
 * table ({@code minecraft:entities/wither}) is intentionally empty, so that first roll drops
 * nothing and the source table can be rolled here with no risk of double-dropping.
 *
 * <p>Injecting into {@code dropCustomDeathLoot} rather than overriding the loot-table lookup also
 * keeps the roll inside the normal vanilla path, which is what makes the Looting enchantment take
 * effect: Looting is resolved from the loot context built by {@code dropFromLootTable}.
 */
@Mixin(WitherBoss.class)
public abstract class WitherLootMixin {
	@Inject(method = "dropCustomDeathLoot", at = @At("HEAD"), cancellable = true)
	private void nothingbutwithers$dropSourceLoot(ServerLevel level, DamageSource source,
			boolean hitByPlayer, CallbackInfo ci) {
		WitherBoss self = (WitherBoss) (Object) this;
		if (!self.hasAttached(ConvertedWitherData.ORIGINAL_ENTITY)) {
			// A player-built Wither keeps the vanilla Nether Star.
			return;
		}

		// Replaces the vanilla body entirely: roll the source mob's table instead of dropping a
		// Nether Star, unless the config explicitly keeps it.
		if (ModConfig.get().suppressNetherStar) {
			ci.cancel();
			WitherConversion.rollSourceLoot(self, level, source, hitByPlayer);
		}
	}
}
