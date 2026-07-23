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
	//      CartographyTableScreenHandler). Kept here so widgets are computed, never guessed. ----
	public static final int ARROW_X = 35;          // arrow/indicator region left
	public static final int ARROW_Y = 31;          // arrow/indicator region top
	public static final int ARROW_W = 28;          // arrow/indicator region width
	public static final int ARROW_H = 21;          // arrow/indicator region height
	public static final int MAP_PREVIEW_LEFT = 67;  // left edge of the big map preview
	public static final int MAP_PREVIEW_TOP = 13;   // top edge of the big map preview
	public static final int MAP_PREVIEW_WIDTH = 66; // width of the big map preview
	public static final int RESULT_SLOT_X = 145;    // result slot top-left
	public static final int RESULT_SLOT_Y = 39;
	public static final int SLOT_SIZE = 16;
	public static final int WIDGET_GAP = 1;         // snug gap between a widget and the arrow

	// ---- RUN and AUTO buttons: same size + same left edge, placed relative to the arrow
	//      (RUN below, AUTO above), kept clear of the map preview / result slot. ----
	public static final int BUTTON_HEIGHT = 16;
	public static final int BUTTON_OFFSET_X = ARROW_X;                              // 35, shared left edge
	public static final int BUTTON_WIDTH = MAP_PREVIEW_LEFT - ARROW_X - 1;          // 31
	public static final int RUN_OFFSET_Y = ARROW_Y + ARROW_H + WIDGET_GAP;          // 53 (below arrow)
	public static final int AUTO_OFFSET_Y = ARROW_Y - WIDGET_GAP - BUTTON_HEIGHT;   // 14 (above arrow)
	public static final String RUN_LABEL_IDLE = "Run";
	public static final String AUTO_LABEL_IDLE = "Auto";
	public static final String BUTTON_LABEL_RUNNING = "Stop";

	// ---- Target-id field: wide, in the gap directly above the map preview (fits large ids). ----
	public static final int FIELD_HEIGHT = 12;
	public static final int FIELD_OFFSET_X = MAP_PREVIEW_LEFT;               // 67, aligned to preview left
	public static final int FIELD_OFFSET_Y = MAP_PREVIEW_TOP - FIELD_HEIGHT; // 1, snug above the preview
	public static final int FIELD_WIDTH = MAP_PREVIEW_WIDTH;                 // 66, preview width (horizontal)
	public static final int FIELD_MAX_LENGTH = 10;

	// ---- Auto-lock pacing (ticks between one lock operation and the next) ----
	public static final int MIN_LOCK_DELAY_TICKS = 1;       // selectable from 1 tick
	public static final int DEFAULT_LOCK_DELAY_TICKS = 10;  // conservative default (~0.5s)
	public static final int MAX_LOCK_DELAY_TICKS = 100;     // slider upper bound (~5s)

	// ---- Internal pacing ----
	public static final int INTER_ACTION_TICKS = 2;         // gap between clicks within one cycle
	public static final int SERVER_WAIT_TIMEOUT_TICKS = 60; // give up waiting on the server after ~3s

	// ---- AUTO loop tuning ----
	public static final int DEFAULT_VERIFY_THRESHOLD = 3;
	public static final int MIN_VERIFY_THRESHOLD = 0;
	public static final int MAX_VERIFY_THRESHOLD = 50;
	public static final int DEFAULT_CONTAINER_TIMEOUT_TICKS = 60;
	public static final int MIN_CONTAINER_TIMEOUT_TICKS = 20;
	public static final int MAX_CONTAINER_TIMEOUT_TICKS = 200;
	public static final int DEFAULT_MIN_ACTION_INTERVAL_TICKS = 1;
	public static final int MIN_ACTION_INTERVAL_TICKS = 1;     // safety floor: never spam
	public static final int MAX_ACTION_INTERVAL_TICKS = 20;
	public static final int VANILLA_ITEM_USE_COOLDOWN_TICKS = 4; // matches MinecraftClient.doItemUse
	public static final int RETRY_LIMIT = 5;                  // hotbar switch / reopen retries
}
