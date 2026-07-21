package io.github.kimitsukiayano.roundmaphunter.autolock;

import io.github.kimitsukiayano.roundmaphunter.RmhConstants;
import io.github.kimitsukiayano.roundmaphunter.RoundMapHunterClient;
import io.github.kimitsukiayano.roundmaphunter.config.RoundMapHunterConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CartographyTableScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.Set;

/**
 * Drives the semi-automatic cartography lock. Everything happens through
 * {@link ClientPlayerInteractionManager#clickSlot} with a plain left-click ({@code PICKUP}),
 * i.e. the exact call the vanilla client makes when a human left-clicks a slot — so the
 * outbound packets are byte-for-byte identical to manual play. No custom packets are ever built.
 *
 * <p>Fairness rules enforced here:
 * <ul>
 *   <li>Never acts while the player's cursor holds an item; if an operation leaves an item on the
 *       cursor it is restored to the inventory.</li>
 *   <li>One click per action tick with a small gap, plus the configurable {@code lockDelayTicks}
 *       between whole lock cycles — no packet spam.</li>
 * </ul>
 *
 * <p>Overshoot handling (predict-from-observed): the result preview only carries the <em>source</em>
 * map's id, and the client cannot read the world map-id counter, so the final locked id is known
 * only after a result is taken. Locked ids are consecutive, so once one is observed the next is
 * {@code lastId + 1}; we stop exactly at the target and abort if a taken id ever passes it. The only
 * unpreventable overshoot is the very first lock (see chat log emitted at start).
 */
public final class AutoLockController {
	public static final AutoLockController INSTANCE = new AutoLockController();

	private AutoLockController() {
	}

	private enum State {
		CHECK_CURSOR,
		INSERT_MAP_PICKUP,
		INSERT_MAP_PLACE,
		GLASS_CHECK,
		GLASS_PICKUP,
		GLASS_PLACE,
		WAIT_PREVIEW,
		DECIDE_AND_TAKE,
		TAKE_PICKUP,
		TAKE_PLACE,
		WAIT_RESULT
	}

	private boolean running;
	private State state;
	private int cooldown;                 // ticks until the next action runs
	private int waitTicks;                // elapsed ticks in a WAIT_* state (timeout guard)

	private CartographyTableScreen boundScreen;

	private boolean hasLastLocked;
	private int lastLockedId;

	private int srcMapSlotId = -1;        // where the current map came from (for cursor restore)
	private int takenDestSlotId = -1;     // where the taken locked map was placed (to read its id)
	private final Set<Integer> skippedMapIds = new HashSet<>(); // maps that produced no result

	// -------------------------------------------------------------------------------------------

	public boolean isRunning() {
		return running;
	}

	/** Button handler: start if idle, stop (manual) if already running. */
	public void toggle(CartographyTableScreen screen) {
		if (running) {
			stopManual();
		} else {
			start(screen);
		}
	}

	private void start(CartographyTableScreen screen) {
		RoundMapHunterConfig config = RoundMapHunterConfig.get();
		if (!config.enabled) {
			return;
		}
		this.boundScreen = screen;
		this.running = true;
		this.state = State.CHECK_CURSOR;
		this.cooldown = 0;
		this.waitTicks = 0;
		this.hasLastLocked = false;
		this.lastLockedId = -1;
		this.srcMapSlotId = -1;
		this.takenDestSlotId = -1;
		this.skippedMapIds.clear();
		chat("§b[RoundMapHunter] auto-lock started (target id = " + config.targetId + ")");
		chat("§7 note: the final map id is only known after a lock is taken; the very first lock "
				+ "cannot be predicted, so start below the target.");
	}

	private void stopManual() {
		running = false;
		chat("§e[RoundMapHunter] stopped by user.");
	}

	// -------------------------------------------------------------------------------------------

	/** Called every client tick. */
	public void tick(MinecraftClient client) {
		if (!running) {
			return;
		}
		// Bail if the cartography screen is no longer the active screen (manual close / switch).
		if (client.player == null || client.currentScreen != boundScreen) {
			running = false;
			return;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}

		CartographyTableScreenHandler handler = boundScreen.getScreenHandler();
		ClientPlayerEntity player = client.player;
		ClientPlayerInteractionManager im = client.interactionManager;
		if (im == null) {
			return;
		}

		try {
			step(client, player, im, handler);
		} catch (Exception e) {
			RoundMapHunterClient.LOGGER.error("[autolock] unexpected error, aborting", e);
			restoreCursorIfNeeded(player, im, handler);
			terminate("§c[RoundMapHunter] internal error — stopped.", true, player);
		}
	}

	private void step(MinecraftClient client, ClientPlayerEntity player,
			ClientPlayerInteractionManager im, CartographyTableScreenHandler handler) {
		RoundMapHunterConfig config = RoundMapHunterConfig.get();
		int target = config.targetId;

		switch (state) {
			case CHECK_CURSOR -> {
				if (!handler.getCursorStack().isEmpty()) {
					// Player is holding something — never touch anything.
					stopBecauseCursorBusy();
					return;
				}
				// If the map slot already holds a map, skip insertion.
				if (!handler.getSlot(CartographyTableScreenHandler.MAP_SLOT_INDEX).getStack().isEmpty()) {
					state = State.GLASS_CHECK;
				} else {
					state = State.INSERT_MAP_PICKUP;
				}
			}
			case INSERT_MAP_PICKUP -> {
				int mapSlot = findUnlockedFilledMapPlayerSlot(client, handler);
				if (mapSlot < 0) {
					terminate("§6[RoundMapHunter] out of unlocked maps — stopping.", true, player);
					return;
				}
				srcMapSlotId = mapSlot;
				click(im, handler, mapSlot, player);   // pick up the map onto the cursor
				gap();
				state = State.INSERT_MAP_PLACE;
			}
			case INSERT_MAP_PLACE -> {
				click(im, handler, CartographyTableScreenHandler.MAP_SLOT_INDEX, player); // place into slot 0
				if (!handler.getCursorStack().isEmpty()) {
					// Placement failed; put the map back and abort.
					restoreCursorIfNeeded(player, im, handler);
					terminate("§c[RoundMapHunter] could not place the map — stopped.", true, player);
					return;
				}
				gap();
				state = State.GLASS_CHECK;
			}
			case GLASS_CHECK -> {
				ItemStack material = handler.getSlot(CartographyTableScreenHandler.MATERIAL_SLOT_INDEX).getStack();
				if (!material.isEmpty() && material.getCount() >= 1) {
					state = State.WAIT_PREVIEW;     // still have glass; go straight to the preview
					waitTicks = 0;
				} else {
					state = State.GLASS_PICKUP;
				}
			}
			case GLASS_PICKUP -> {
				int glassSlot = findGlassPanePlayerSlot(handler);
				if (glassSlot < 0) {
					terminate("§6[RoundMapHunter] out of glass panes — stopping.", true, player);
					return;
				}
				click(im, handler, glassSlot, player);  // pick up the whole glass stack
				gap();
				state = State.GLASS_PLACE;
			}
			case GLASS_PLACE -> {
				click(im, handler, CartographyTableScreenHandler.MATERIAL_SLOT_INDEX, player); // place stack
				if (!handler.getCursorStack().isEmpty()) {
					restoreCursorIfNeeded(player, im, handler);
					terminate("§c[RoundMapHunter] could not place glass — stopped.", true, player);
					return;
				}
				gap();
				waitTicks = 0;
				state = State.WAIT_PREVIEW;
			}
			case WAIT_PREVIEW -> {
				// The result is computed server-side; wait for it to arrive.
				ItemStack result = handler.getSlot(CartographyTableScreenHandler.RESULT_SLOT_INDEX).getStack();
				if (!result.isEmpty()) {
					state = State.DECIDE_AND_TAKE;
					return;
				}
				if (++waitTicks > RmhConstants.SERVER_WAIT_TIMEOUT_TICKS) {
					// No preview: the inserted map was probably already locked. Skip it.
					handleNoPreview(player, im, handler);
				}
			}
			case DECIDE_AND_TAKE -> {
				// Predictive overshoot guard (safe for every lock after the first observation).
				if (hasLastLocked && (lastLockedId + 1) > target) {
					terminate("§6[RoundMapHunter] next lock (#" + (lastLockedId + 1)
							+ ") would pass target #" + target + " — stopping without locking.", true, player);
					return;
				}
				// Inventory-full guard: the taken locked map needs an empty slot (maps do not stack).
				int dest = findEmptyPlayerSlot(handler);
				if (dest < 0) {
					terminate("§6[RoundMapHunter] inventory full — stopping.", true, player);
					return;
				}
				takenDestSlotId = dest;
				state = State.TAKE_PICKUP;
			}
			case TAKE_PICKUP -> {
				click(im, handler, CartographyTableScreenHandler.RESULT_SLOT_INDEX, player); // take result
				gap();
				state = State.TAKE_PLACE;
			}
			case TAKE_PLACE -> {
				click(im, handler, takenDestSlotId, player); // drop into the reserved empty slot
				if (!handler.getCursorStack().isEmpty()) {
					restoreCursorIfNeeded(player, im, handler);
					terminate("§c[RoundMapHunter] could not stow the locked map — stopped.", true, player);
					return;
				}
				waitTicks = 0;
				state = State.WAIT_RESULT;
			}
			case WAIT_RESULT -> {
				// Wait for the server to replace the predicted copy with the real locked map.
				ItemStack stack = handler.getSlot(takenDestSlotId).getStack();
				boolean resolved = stack.contains(DataComponentTypes.MAP_ID)
						&& !stack.contains(DataComponentTypes.MAP_POST_PROCESSING);
				if (resolved) {
					MapIdComponent id = stack.get(DataComponentTypes.MAP_ID);
					onLocked(player, id != null ? id.id() : -1, target);
					return;
				}
				if (++waitTicks > RmhConstants.SERVER_WAIT_TIMEOUT_TICKS) {
					terminate("§c[RoundMapHunter] timed out waiting for the locked map id — stopped.", true, player);
				}
			}
			default -> running = false;
		}
	}

	// -------------------------------------------------------------------------------------------

	private void onLocked(ClientPlayerEntity player, int lockedId, int target) {
		lastLockedId = lockedId;
		hasLastLocked = true;

		if (lockedId == target) {
			// Success: keep the GUI open, play a client-local firework blast, log.
			player.playSound(SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.0f);
			chat("§a[RoundMapHunter] success! locked map #" + lockedId + " (target reached).");
			running = false;
			return;
		}
		if (lockedId > target) {
			terminate("§6[RoundMapHunter] overshot: locked #" + lockedId + " > target #" + target
					+ " (first lock could not be predicted) — stopping.", true, player);
			return;
		}
		// lockedId < target: keep going after the configured delay.
		chat("§7[RoundMapHunter] locked #" + lockedId + ", continuing toward #" + target + "…");
		cooldown = RoundMapHunterConfig.get().lockDelayTicks;
		state = State.CHECK_CURSOR;
	}

	private void handleNoPreview(ClientPlayerEntity player, ClientPlayerInteractionManager im,
			CartographyTableScreenHandler handler) {
		// The map in slot 0 gave no result (already locked, or an unexpected combo). Skip it:
		// remember its id and move it back to the inventory, then look for another map.
		ItemStack inMap = handler.getSlot(CartographyTableScreenHandler.MAP_SLOT_INDEX).getStack();
		if (inMap.contains(DataComponentTypes.MAP_ID)) {
			MapIdComponent id = inMap.get(DataComponentTypes.MAP_ID);
			if (id != null) {
				skippedMapIds.add(id.id());
			}
		}
		int dest = findEmptyPlayerSlot(handler);
		if (dest < 0) {
			terminate("§6[RoundMapHunter] inventory full while skipping an unusable map — stopping.", true, player);
			return;
		}
		click(im, handler, CartographyTableScreenHandler.MAP_SLOT_INDEX, player); // pick up the map
		gap();
		click(im, handler, dest, player);                                        // stow it
		restoreCursorIfNeeded(player, im, handler);
		state = State.CHECK_CURSOR;
	}

	private void stopBecauseCursorBusy() {
		running = false;
		chat("§e[RoundMapHunter] cursor is holding an item — paused (GUI left open).");
	}

	private void terminate(String message, boolean closeGui, ClientPlayerEntity player) {
		running = false;
		chat(message);
		if (closeGui && player != null) {
			player.closeHandledScreen();
		}
	}

	// -------------------------------------------------------------------------------------------
	// Slot helpers — all resolve slot ids from the handler; nothing is hardcoded.

	private boolean isPlayerSlot(CartographyTableScreenHandler handler, Slot slot, PlayerInventory inv) {
		return slot.inventory == inv;
	}

	private int findUnlockedFilledMapPlayerSlot(MinecraftClient client, CartographyTableScreenHandler handler) {
		PlayerInventory inv = client.player.getInventory();
		for (Slot slot : handler.slots) {
			if (!isPlayerSlot(handler, slot, inv)) {
				continue;
			}
			ItemStack stack = slot.getStack();
			if (!stack.isOf(Items.FILLED_MAP) || !stack.contains(DataComponentTypes.MAP_ID)) {
				continue;
			}
			MapIdComponent id = stack.get(DataComponentTypes.MAP_ID);
			if (id != null && skippedMapIds.contains(id.id())) {
				continue;
			}
			// Prefer maps we can confirm are unlocked; if the MapState is not synced, try optimistically.
			MapState ms = client.world == null ? null : FilledMapItem.getMapState(stack, client.world);
			if (ms == null || !ms.locked) {
				return slot.id;
			}
		}
		return -1;
	}

	private int findGlassPanePlayerSlot(CartographyTableScreenHandler handler) {
		PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
		for (Slot slot : handler.slots) {
			if (isPlayerSlot(handler, slot, inv) && slot.getStack().isOf(Items.GLASS_PANE)) {
				return slot.id;
			}
		}
		return -1;
	}

	private int findEmptyPlayerSlot(CartographyTableScreenHandler handler) {
		PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
		for (Slot slot : handler.slots) {
			if (isPlayerSlot(handler, slot, inv) && slot.getStack().isEmpty()) {
				return slot.id;
			}
		}
		return -1;
	}

	/** If our cursor ended up holding something, put it back into any empty inventory slot. */
	private void restoreCursorIfNeeded(ClientPlayerEntity player, ClientPlayerInteractionManager im,
			CartographyTableScreenHandler handler) {
		if (handler.getCursorStack().isEmpty()) {
			return;
		}
		int dest = findEmptyPlayerSlot(handler);
		if (dest >= 0) {
			click(im, handler, dest, player);
		} else {
			RoundMapHunterClient.LOGGER.warn("[autolock] cursor holds an item but no empty slot to restore it");
		}
	}

	// -------------------------------------------------------------------------------------------

	/** A single left-click on a slot — identical to a human clicking it. */
	private void click(ClientPlayerInteractionManager im, CartographyTableScreenHandler handler,
			int slotId, ClientPlayerEntity player) {
		im.clickSlot(handler.syncId, slotId, 0, SlotActionType.PICKUP, player);
	}

	/** Small pause between individual clicks so we never spam packets within a cycle. */
	private void gap() {
		cooldown = RmhConstants.INTER_ACTION_TICKS;
	}

	private void chat(String msg) {
		RoundMapHunterClient.sendChat(Text.literal(msg));
	}
}
