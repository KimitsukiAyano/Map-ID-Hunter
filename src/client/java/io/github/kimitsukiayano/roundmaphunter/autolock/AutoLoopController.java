package io.github.kimitsukiayano.roundmaphunter.autolock;

import io.github.kimitsukiayano.roundmaphunter.RoundMapHunterClient;
import net.minecraft.client.gui.screen.ingame.CartographyTableScreen;
import net.minecraft.text.Text;

/**
 * Full auto-loop (AUTO button): lock → discard unwanted maps → close GUI → craft m maps from empty
 * maps → reopen the table → repeat until the target id is reached.
 *
 * <p>Phase 1 stub: the button is wired and toggles, but the loop body is not implemented yet — a
 * press only logs. The state machine is added in phase 3 (after the field/focus behaviour is
 * confirmed in-game). {@link #isRunning()} stays {@code false} so the RUN button is never disabled
 * by a phantom AUTO run.
 */
public final class AutoLoopController {
	public static final AutoLoopController INSTANCE = new AutoLoopController();

	private AutoLoopController() {
	}

	private boolean running;

	public boolean isRunning() {
		return running;
	}

	public void toggle(CartographyTableScreen screen) {
		// Phase 1: no loop yet — just confirm the wiring.
		RoundMapHunterClient.sendChat(Text.literal("§d[RoundMapHunter] AUTO pressed (full auto-loop not wired yet)"));
	}
}
