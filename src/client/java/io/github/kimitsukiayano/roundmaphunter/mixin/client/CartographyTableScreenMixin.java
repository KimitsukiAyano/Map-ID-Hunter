package io.github.kimitsukiayano.roundmaphunter.mixin.client;

import io.github.kimitsukiayano.roundmaphunter.RmhConstants;
import io.github.kimitsukiayano.roundmaphunter.RoundMapHunterClient;
import io.github.kimitsukiayano.roundmaphunter.autolock.AutoLockController;
import io.github.kimitsukiayano.roundmaphunter.config.RoundMapHunterConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.CartographyTableScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the auto-lock toggle button to the cartography table screen.
 *
 * <p>{@code CartographyTableScreen} does not override {@code init()}, so we hook {@code render}
 * and lazily (re)attach the button when it is missing. Checking membership each frame keeps
 * the button alive across window resizes (which rebuild the widget list) and lets a config
 * toggle add/remove it live.
 *
 * <p>The mixin extends {@link HandledScreen} so it can read the protected layout fields
 * ({@code x}/{@code y}) and call {@code addDrawableChild}/{@code remove}; at runtime it is merged
 * into {@code CartographyTableScreen}, whose superclass is exactly this type.
 */
@Mixin(CartographyTableScreen.class)
public abstract class CartographyTableScreenMixin extends HandledScreen<CartographyTableScreenHandler> {

	private CartographyTableScreenMixin(CartographyTableScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
	}

	@Unique
	private ButtonWidget roundmaphunter$button;
	@Unique
	private boolean roundmaphunter$loggedSlots;

	@Inject(method = "render", at = @At("HEAD"))
	private void roundmaphunter$ensureButton(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		RoundMapHunterConfig config = RoundMapHunterConfig.get();

		// Feature disabled: make sure no button is present, then bail.
		if (!config.enabled) {
			if (roundmaphunter$button != null) {
				this.remove(roundmaphunter$button);
				roundmaphunter$button = null;
			}
			return;
		}

		// (Re)attach the button if it is missing (fresh open or post-resize rebuild).
		if (roundmaphunter$button == null || !this.children().contains(roundmaphunter$button)) {
			int bx = this.x + RmhConstants.BUTTON_OFFSET_X;
			int by = this.y + RmhConstants.BUTTON_OFFSET_Y;
			roundmaphunter$button = ButtonWidget
					.builder(Text.literal(RmhConstants.BUTTON_LABEL_IDLE),
							b -> AutoLockController.INSTANCE.toggle((CartographyTableScreen) (Object) this))
					.dimensions(bx, by, RmhConstants.BUTTON_WIDTH, RmhConstants.BUTTON_HEIGHT)
					.build();
			this.addDrawableChild(roundmaphunter$button);
		}

		// Live label so the button shows running vs idle.
		roundmaphunter$button.setMessage(Text.literal(
				AutoLockController.INSTANCE.isRunning() ? RmhConstants.BUTTON_LABEL_RUNNING : RmhConstants.BUTTON_LABEL_IDLE));

		// One-time diagnostic: prove the slot indices are resolved from the handler, not hardcoded.
		if (!roundmaphunter$loggedSlots) {
			roundmaphunter$loggedSlots = true;
			RoundMapHunterClient.LOGGER.info(
					"[cartography] slots via handler -> map={}, material={}, result={} (total slots={})",
					CartographyTableScreenHandler.MAP_SLOT_INDEX,
					CartographyTableScreenHandler.MATERIAL_SLOT_INDEX,
					CartographyTableScreenHandler.RESULT_SLOT_INDEX,
					this.getScreenHandler().slots.size());
		}
	}
}
