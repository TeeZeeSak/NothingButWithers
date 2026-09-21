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
 * Gives a converted Wither the loot table of the mob it replaced, and optionally withholds the
 * Nether Star.
 *
 * <p>The source roll happens here because {@code dropCustomDeathLoot} runs immediately after
 * {@code LivingEntity.dropAllDeathLoot} has already rolled the entity's own loot table. A Wither's
 * table ({@code minecraft:entities/wither}) is intentionally empty, so that first roll drops nothing
 * and the source table can be rolled here with no risk of double-dropping.
 *
 * <p>The Nether Star is not part of that loot table: {@code WitherBoss.dropCustomDeathLoot} spawns it
 * directly in code. Cancelling this method is therefore what suppresses the star, while rolling the
 * source table separately in the other branch. The two concerns are independent, so
 * {@code suppressNetherStar=false} still yields the original mob's loot plus the star rather than
 * silently reverting to vanilla Wither drops.
 *
 * <p>Injecting here rather than overriding the loot-table lookup also keeps the roll inside the
 * normal vanilla path, which is what makes the Looting enchantment take effect: Looting is resolved
 * from the loot context built by {@code dropFromLootTable}.
 */
@Mixin(WitherBoss.class)
public abstract class WitherLootMixin {
	@Inject(method = "dropCustomDeathLoot", at = @At("HEAD"), cancellable = true)
	private void nothingbutwithers$dropSourceLoot(ServerLevel level, DamageSource source,
			boolean hitByPlayer, CallbackInfo ci) {
		WitherBoss self = (WitherBoss) (Object) this;
		if (!self.hasAttached(ConvertedWitherData.LOOT_TABLE)) {
			// A player-built Wither keeps the vanilla body, including the Nether Star.
			return;
		}

		// Cancelling replaces the vanilla body, which is what suppresses the Nether Star. When the
		// star is not suppressed the vanilla body is left intact and only the source loot is added.
		if (ModConfig.get().suppressNetherStar) {
			ci.cancel();
		}

		WitherConversion.rollSourceLoot(self, level, source, hitByPlayer);
	}
}
