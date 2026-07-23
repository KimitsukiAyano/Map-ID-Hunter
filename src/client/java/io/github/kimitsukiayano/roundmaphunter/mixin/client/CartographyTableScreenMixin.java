package io.github.kimitsukiayano.roundmaphunter.mixin.client;

import io.github.kimitsukiayano.roundmaphunter.RmhConstants;
import io.github.kimitsukiayano.roundmaphunter.RoundMapHunterClient;
import io.github.kimitsukiayano.roundmaphunter.autolock.AutoLockController;
import io.github.kimitsukiayano.roundmaphunter.autolock.AutoLoopController;
import io.github.kimitsukiayano.roundmaphunter.config.RoundMapHunterConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.CartographyTableScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the cartography-screen widgets: the RUN button (below the arrow, fair single-container lock),
 * the AUTO button (above the arrow, full auto-loop), and the target-id field (below the result slot).
 *
 * <p>{@code CartographyTableScreen} does not override {@code init()}, so we hook {@code render} and
 * lazily (re)attach widgets when missing (survives window resize and live config toggles). The mixin
 * extends {@link HandledScreen} for protected layout fields and to override {@code keyPressed}.
 */
@Mixin(CartographyTableScreen.class)
public abstract class CartographyTableScreenMixin extends HandledScreen<CartographyTableScreenHandler> {

	private CartographyTableScreenMixin(CartographyTableScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
	}

	@Unique
	private ButtonWidget roundmaphunter$runButton;
	@Unique
	private ButtonWidget roundmaphunter$autoButton;
	@Unique
	private TextFieldWidget roundmaphunter$targetField;
	@Unique
	private boolean roundmaphunter$targetValid = true;
	@Unique
	private boolean roundmaphunter$loggedSlots;

	@Unique
	private CartographyTableScreen roundmaphunter$self() {
		return (CartographyTableScreen) (Object) this;
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void roundmaphunter$ui(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		RoundMapHunterConfig config = RoundMapHunterConfig.get();

		if (!config.enabled) {
			roundmaphunter$removeAll();
			return;
		}

		// Target-id field (below the result slot).
		if (roundmaphunter$targetField == null || !this.children().contains(roundmaphunter$targetField)) {
			roundmaphunter$buildTargetField(config);
		}

		// RUN button (below the arrow) — unchanged fair behaviour.
		if (roundmaphunter$runButton == null || !this.children().contains(roundmaphunter$runButton)) {
			roundmaphunter$runButton = ButtonWidget
					.builder(Text.literal(RmhConstants.RUN_LABEL_IDLE),
							b -> AutoLockController.INSTANCE.toggle(roundmaphunter$self()))
					.dimensions(this.x + RmhConstants.BUTTON_OFFSET_X, this.y + RmhConstants.RUN_OFFSET_Y,
							RmhConstants.BUTTON_WIDTH, RmhConstants.BUTTON_HEIGHT)
					.build();
			this.addDrawableChild(roundmaphunter$runButton);
		}

		// AUTO button (above the arrow) — has its own show toggle.
		if (config.showAutoButton) {
			if (roundmaphunter$autoButton == null || !this.children().contains(roundmaphunter$autoButton)) {
				roundmaphunter$autoButton = ButtonWidget
						.builder(Text.literal(RmhConstants.AUTO_LABEL_IDLE),
								b -> AutoLoopController.INSTANCE.toggle(roundmaphunter$self()))
						.dimensions(this.x + RmhConstants.BUTTON_OFFSET_X, this.y + RmhConstants.AUTO_OFFSET_Y,
								RmhConstants.BUTTON_WIDTH, RmhConstants.BUTTON_HEIGHT)
						.build();
				this.addDrawableChild(roundmaphunter$autoButton);
			}
		} else if (roundmaphunter$autoButton != null) {
			this.remove(roundmaphunter$autoButton);
			roundmaphunter$autoButton = null;
		}

		roundmaphunter$updateButtonStates();

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

	@Unique
	private void roundmaphunter$buildTargetField(RoundMapHunterConfig config) {
		roundmaphunter$targetField = new TextFieldWidget(this.textRenderer,
				this.x + RmhConstants.FIELD_OFFSET_X, this.y + RmhConstants.FIELD_OFFSET_Y,
				RmhConstants.FIELD_WIDTH, RmhConstants.FIELD_HEIGHT, Text.literal("T"));
		roundmaphunter$targetField.setMaxLength(RmhConstants.FIELD_MAX_LENGTH);
		roundmaphunter$targetField.setTextPredicate(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
		roundmaphunter$targetField.setText(String.valueOf(config.targetId));
		roundmaphunter$targetField.setChangedListener(this::roundmaphunter$onTargetChanged);
		roundmaphunter$targetValid = true; // current config.targetId is always valid (>= 0)
		this.addDrawableChild(roundmaphunter$targetField);
	}

	@Unique
	private void roundmaphunter$onTargetChanged(String text) {
		if (text == null || text.isEmpty()) {
			roundmaphunter$targetValid = false;
			return;
		}
		try {
			long value = Long.parseLong(text);
			if (value < 0 || value > Integer.MAX_VALUE) {
				roundmaphunter$targetValid = false;
				return;
			}
			roundmaphunter$targetValid = true;
			RoundMapHunterConfig config = RoundMapHunterConfig.get();
			if (config.targetId != (int) value) {
				config.targetId = (int) value;
				config.save(); // shared with the YACL screen
			}
		} catch (NumberFormatException e) {
			roundmaphunter$targetValid = false;
		}
	}

	@Unique
	private void roundmaphunter$updateButtonStates() {
		boolean runRunning = AutoLockController.INSTANCE.isRunning();
		boolean autoRunning = AutoLoopController.INSTANCE.isRunning();

		roundmaphunter$runButton.setMessage(Text.literal(
				runRunning ? RmhConstants.BUTTON_LABEL_RUNNING : RmhConstants.RUN_LABEL_IDLE));
		// Enabled when this one is running (so it can stop), or when the target is valid and the other
		// is idle (mutual exclusion + validity gating).
		roundmaphunter$runButton.active = runRunning || (roundmaphunter$targetValid && !autoRunning);

		if (roundmaphunter$autoButton != null) {
			roundmaphunter$autoButton.setMessage(Text.literal(
					autoRunning ? RmhConstants.BUTTON_LABEL_RUNNING : RmhConstants.AUTO_LABEL_IDLE));
			roundmaphunter$autoButton.active = autoRunning || (roundmaphunter$targetValid && !runRunning);
		}
	}

	@Unique
	private void roundmaphunter$removeAll() {
		if (roundmaphunter$runButton != null) {
			this.remove(roundmaphunter$runButton);
			roundmaphunter$runButton = null;
		}
		if (roundmaphunter$autoButton != null) {
			this.remove(roundmaphunter$autoButton);
			roundmaphunter$autoButton = null;
		}
		if (roundmaphunter$targetField != null) {
			this.remove(roundmaphunter$targetField);
			roundmaphunter$targetField = null;
		}
	}

	/**
	 * While the target field is focused, consume every key except Escape so that number keys do not
	 * trigger the vanilla hotbar swap / item drop. Editing keys are still forwarded to the field, and
	 * digit characters arrive separately via {@code charTyped}, so typing works normally. Escape falls
	 * through to the vanilla handler so the screen can still be closed.
	 */
	@Override
	public boolean keyPressed(KeyInput input) {
		if (roundmaphunter$targetField != null && roundmaphunter$targetField.isFocused()
				&& input.getKeycode() != GLFW.GLFW_KEY_ESCAPE) {
			roundmaphunter$targetField.keyPressed(input);
			return true;
		}
		return super.keyPressed(input);
	}
}
