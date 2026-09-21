package com.nothingbutwithers.mixin;

import com.nothingbutwithers.config.ModConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bounds the terrain destruction performed by Withers.
 *
 * <p>Every projectile attack funnels through {@code performRangedAttack}, whose trailing boolean
 * selects a tracking (black) skull or a destructive (blue) one. Withers separately charge a
 * block-removal sweep from {@code customServerAiStep}, and being hurt arms that charge again. With
 * the number of Withers this mod creates, those two together reduce the world to craters and are
 * the largest single source of block-update load.
 *
 * <p>Damage is unaffected. A skull still hits for its full amount and still applies the Wither
 * effect; only its block breaking is dropped, which keeps the combat threat without the excavation.
 */
@Mixin(WitherBoss.class)
public abstract class WitherGriefMixin {
	/**
	 * Forces the attack's destructive flag off. Changing the argument is safer than re-invoking the
	 * attack, because {@code performRangedAttack} is private and the AI's bookkeeping around the
	 * call stays untouched.
	 */
	@ModifyVariable(method = "performRangedAttack(IDDDZ)V", at = @At("HEAD"), argsOnly = true)
	private boolean nothingbutwithers$limitDestructiveSkull(boolean destructive) {
		return ModConfig.get().witherSkullsDestroyTerrain && destructive;
	}

	@Inject(method = "customServerAiStep", at = @At("TAIL"))
	private void nothingbutwithers$clampBlockBreaker(ServerLevel level, CallbackInfo ci) {
		clamp();
	}

	@Inject(method = "hurtServer", at = @At("TAIL"))
	private void nothingbutwithers$clampBlockBreakerOnHurt(ServerLevel level, DamageSource source,
			float amount, CallbackInfoReturnable<Boolean> cir) {
		clamp();
	}

	/** Keeps the block-removal charge at zero so no sweep is ever scheduled. */
	private void clamp() {
		if (ModConfig.get().withersDestroyBlocks) {
			return;
		}
		WitherBossDuck duck = (WitherBossDuck) this;
		if (duck.nothingbutwithers$destroyBlocksTick() > 0) {
			duck.nothingbutwithers$setDestroyBlocksTick(0);
		}
	}
}
