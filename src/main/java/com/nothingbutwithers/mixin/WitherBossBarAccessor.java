package com.nothingbutwithers.mixin;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the Wither's boss event so its visibility can be controlled. */
@Mixin(WitherBoss.class)
public interface WitherBossBarAccessor {
	@Accessor("bossEvent")
	ServerBossEvent nothingbutwithers$getBossEvent();
}
