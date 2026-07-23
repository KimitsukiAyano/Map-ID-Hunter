package io.github.kimitsukiayano.roundmaphunter.mixin.client;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read/adjust the vanilla item-use cooldown so AUTO can respect (never shorten) it. */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
	@Accessor("itemUseCooldown")
	int roundmaphunter$getItemUseCooldown();

	@Accessor("itemUseCooldown")
	void roundmaphunter$setItemUseCooldown(int value);
}
