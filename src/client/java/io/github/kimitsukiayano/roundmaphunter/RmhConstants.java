package io.github.kimitsukiayano.roundmaphunter;

/**
 * Single home for all tuning constants / magic numbers (plan step 7).
 * Button coordinates are relative to the cartography GUI's top-left (HandledScreen x/y),
 * so they stay correct regardless of window size.
 */
public final class RmhConstants {
	private RmhConstants() {
	}

	// ---- Vanilla cartography layout, relative to the GUI top-left (from CartographyTableScreen /
	//      CartographyTableScreenHandler). Kept here so the button is computed, never guessed. ----
	public static final int ARROW_X = 35;          // arrow/indicator region left
	public static final int ARROW_Y = 31;          // arrow/indicator region top
	public static final int ARROW_W = 28;          // arrow/indicator region width
	public static final int ARROW_H = 21;          // arrow/indicator region height
	public static final int MAP_PREVIEW_LEFT = 67; // left edge of the big map preview (do not overlap)
	public static final int BUTTON_BELOW_GAP = 1;  // snug gap under the arrow

	// ---- Derived button box: small, left-aligned to the arrow, tucked just under it, and kept
	//      clear of the map preview / result slot. ----
	public static final int BUTTON_HEIGHT = 16;
	public static final int BUTTON_OFFSET_X = ARROW_X;                                 // 35
	public static final int BUTTON_OFFSET_Y = ARROW_Y + ARROW_H + BUTTON_BELOW_GAP;    // 53
	public static final int BUTTON_WIDTH = MAP_PREVIEW_LEFT - ARROW_X - 1;             // 31
	public static final String BUTTON_LABEL_IDLE = "Run";
	public static final String BUTTON_LABEL_RUNNING = "Stop";

	// ---- Auto-lock pacing (ticks between one lock operation and the next) ----
	public static final int MIN_LOCK_DELAY_TICKS = 1;       // selectable from 1 tick
	public static final int DEFAULT_LOCK_DELAY_TICKS = 10;  // conservative default (~0.5s)
	public static final int MAX_LOCK_DELAY_TICKS = 100;     // slider upper bound (~5s)

	// ---- Internal pacing ----
	public static final int INTER_ACTION_TICKS = 2;         // gap between clicks within one cycle
	public static final int SERVER_WAIT_TIMEOUT_TICKS = 60; // give up waiting on the server after ~3s
}
