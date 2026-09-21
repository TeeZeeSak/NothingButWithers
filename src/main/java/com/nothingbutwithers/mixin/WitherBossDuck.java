package com.nothingbutwithers.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.boss.wither.WitherBoss;

/** Private-state accessors for {@link WitherBoss} used by {@link WitherGriefMixin}. */
@Mixin(WitherBoss.class)
public interface WitherBossDuck {
	@Accessor("destroyBlocksTick")
	int nothingbutwithers$destroyBlocksTick();

	@Accessor("destroyBlocksTick")
	void nothingbutwithers$setDestroyBlocksTick(int value);
}
