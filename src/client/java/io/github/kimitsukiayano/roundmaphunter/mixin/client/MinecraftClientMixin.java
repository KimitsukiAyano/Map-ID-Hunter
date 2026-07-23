package io.github.kimitsukiayano.roundmaphunter.mixin.client;

import io.github.kimitsukiayano.roundmaphunter.autolock.AutoLoopController;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the AUTO loop from the same tick position vanilla uses for item use/placement: right before
 * {@code ClientWorld.tickEntities()} (which sends the player's movement packets). This keeps AUTO's
 * {@code interactItem}/{@code interactBlock}/{@code clickSlot} packets ahead of the movement packet,
 * matching vanilla ordering — unlike END_CLIENT_TICK (after movement). This point runs whether or not
 * a screen is open (tickEntities is only gated by world != null && !paused), so it covers both the
 * in-GUI locking and the closed-GUI crafting phases. The RUN button is unaffected (it does no world
 * use/placement and still runs from END_CLIENT_TICK).
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
	@Inject(
			method = "tick()V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/world/ClientWorld;tickEntities()V",
					shift = At.Shift.BEFORE))
	private void roundmaphunter$driveAutoBeforeMovement(CallbackInfo ci) {
		AutoLoopController.INSTANCE.tick((MinecraftClient) (Object) this);
	}
}
