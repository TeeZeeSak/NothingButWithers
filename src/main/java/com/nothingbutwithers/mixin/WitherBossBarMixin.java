package com.nothingbutwithers.mixin;

import com.nothingbutwithers.conversion.ConvertedWitherData;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the boss bar of converted Withers.
 *
 * <p>With hundreds of Withers loaded, vanilla stacks one boss bar per entity until the screen
 * becomes unusable. Suppressing at the source is enough: {@code ServerBossEvent.addPlayer} only
 * contacts clients while the event is visible, and clearing visibility sends the matching remove
 * packet. No client-side code is required.
 */
@Mixin(WitherBoss.class)
public abstract class WitherBossBarMixin {
	/**
	 * {@code startSeenByPlayer} runs the moment the Wither is shown to a player, and is where
	 * vanilla adds that player to the boss bar. A converted Wither is guaranteed to carry its marker
	 * by then, so visibility is cleared first and the vanilla add becomes inert.
	 */
	@Inject(method = "startSeenByPlayer", at = @At("HEAD"))
	private void nothingbutwithers$hideBarForConverted(net.minecraft.server.level.ServerPlayer player,
			CallbackInfo ci) {
		WitherBoss self = (WitherBoss) (Object) this;
		if (self.hasAttached(ConvertedWitherData.ORIGINAL_ENTITY)) {
			((WitherBossBarAccessor) self).nothingbutwithers$getBossEvent().setVisible(false);
		}
	}
}
