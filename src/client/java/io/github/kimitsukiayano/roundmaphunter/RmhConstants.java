package io.github.kimitsukiayano.roundmaphunter;

/**
 * Single home for all tuning constants / magic numbers (plan step 7).
 * Button coordinates are relative to the cartography GUI's top-left (HandledScreen x/y),
 * so they stay correct regardless of window size.
 */
public final class RmhConstants {
	private RmhConstants() {
	}

	// ---- Cartography auto-lock button (offsets from the GUI top-left corner) ----
	// The cartography arrow sits center-right; the button is placed just below it.
	public static final int BUTTON_OFFSET_X = 63;
	public static final int BUTTON_OFFSET_Y = 58;
	public static final int BUTTON_WIDTH = 50;
	public static final int BUTTON_HEIGHT = 16;
	public static final String BUTTON_LABEL_IDLE = "Auto-Lock";
	public static final String BUTTON_LABEL_RUNNING = "Stop";

	// ---- Auto-lock pacing (ticks between one lock operation and the next) ----
	public static final int MIN_LOCK_DELAY_TICKS = 5;       // safety floor (~0.25s @ 20 tps)
	public static final int DEFAULT_LOCK_DELAY_TICKS = 10;  // conservative default (~0.5s)
	public static final int MAX_LOCK_DELAY_TICKS = 100;     // slider upper bound (~5s)
}
