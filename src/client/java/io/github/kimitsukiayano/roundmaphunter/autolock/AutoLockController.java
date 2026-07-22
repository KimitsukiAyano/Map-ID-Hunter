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
 * Drives the semi-automatic cartography lock. Every item move is a single
 * {@code clickSlot(syncId, slotId, 0, SlotActionType.QUICK_MOVE, player)} — the exact call the
 * vanilla client makes when a human shift-clicks a slot. The cartography handler's own
 * {@code quickMove} routing then places the item (verified against 1.21.11):
 * <ul>
 *   <li>a filled map (has {@code MAP_ID}) shift-clicked from the inventory → the map slot (0) only;</li>
 *   <li>a glass pane → the material slot (1) only;</li>
 *   <li>the result slot (2) → back into the player inventory.</li>
 * </ul>
 * So outbound packets are byte-for-byte identical to manual play, no custom packets are built, and
 * because shift-click never uses the cursor we never disturb an item the player is holding.
 *
 * <p>Overshoot handling (predict-from-observed): the preview only carries the <em>source</em> map's
 * id and the client cannot read the world map-id counter, so the final locked id is known only after
 * a result is taken. Locked ids are consecutive, so after the first observed lock the next is
 * {@code lastId + 1}; we stop exactly at the target and abort if a taken id ever passes it. Only the
 * very first lock cannot be predicted.
 */
public final class AutoLockController {
	public static final AutoLockController INSTANCE = new AutoLockController();

	private AutoLockController() {
	}

	private enum State {
		CHECK_CURSOR,
		INSERT_MAP,
		VERIFY_MAP,
		GLASS_CHECK,
		INSERT_GLASS,
		WAIT_PREVIEW,
		DECIDE_AND_TAKE,
		TAKE,
		WAIT_RESULT
	}

	private boolean running;
	private State state;
	private int cooldown;   // ticks until the next action runs
	private int waitTicks;  // elapsed ticks in a WAIT_* state (timeout guard)

	private CartographyTableScreen boundScreen;

	private boolean hasLastLocked;
	private int lastLockedId;

	private int srcMapSlotId = -1;                          // map we just shift-clicked (for skip)
	private final Set<Integer> skippedMapIds = new HashSet<>();
	private final Set<Integer> emptyBeforeTake = new HashSet<>(); // empty player slots before a take

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
		this.skippedMapIds.clear();
		this.emptyBeforeTake.clear();
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
		if (client.player == null || client.interactionManager == null || client.currentScreen != boundScreen) {
			running = false; // manual close / screen switch — leave everything as-is
			return;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		try {
			step(client, client.player, client.interactionManager, boundScreen.getScreenHandler());
		} catch (Exception e) {
			RoundMapHunterClient.LOGGER.error("[autolock] unexpected error, aborting", e);
			terminate("§c[RoundMapHunter] internal error — stopped.", true, client.player);
		}
	}

	private void step(MinecraftClient client, ClientPlayerEntity player,
			ClientPlayerInteractionManager im, CartographyTableScreenHandler handler) {
		int target = RoundMapHunterConfig.get().targetId;

		switch (state) {
			case CHECK_CURSOR -> {
				if (!handler.getCursorStack().isEmpty()) {
					stopBecauseCursorBusy();          // never touch anything while the player holds an item
					return;
				}
				state = mapSlotStack(handler).isEmpty() ? State.INSERT_MAP : State.GLASS_CHECK;
			}
			case INSERT_MAP -> {
				int mapSlot = findUnlockedFilledMapPlayerSlot(client, handler);
				if (mapSlot < 0) {
					terminate("§6[RoundMapHunter] out of unlocked maps — stopping.", true, player);
					return;
				}
				srcMapSlotId = mapSlot;
				quickMove(im, handler, mapSlot, player); // shift-click: routes to the map slot (0)
				gap();
				state = State.VERIFY_MAP;
			}
			case VERIFY_MAP -> {
				if (!mapSlotStack(handler).isEmpty()) {
					state = State.GLASS_CHECK;
				} else {
					// The map did not move (unexpected). Skip it and try another.
					int id = srcMapSlotId >= 0 ? mapId(handler.getSlot(srcMapSlotId).getStack()) : -1;
					if (id >= 0) {
						skippedMapIds.add(id);
					}
					state = State.INSERT_MAP;
				}
			}
			case GLASS_CHECK -> {
				ItemStack material = materialSlotStack(handler);
				if (!material.isEmpty() && material.getCount() >= 1) {
					waitTicks = 0;
					state = State.WAIT_PREVIEW;      // still have glass; do not refill
				} else {
					state = State.INSERT_GLASS;
				}
			}
			case INSERT_GLASS -> {
				int glassSlot = findGlassPanePlayerSlot(handler);
				if (glassSlot < 0) {
					terminate("§6[RoundMapHunter] out of glass panes — stopping.", true, player);
					return;
				}
				quickMove(im, handler, glassSlot, player); // shift-click: routes the whole stack to slot 1
				gap();
				waitTicks = 0;
				state = State.WAIT_PREVIEW;
			}
			case WAIT_PREVIEW -> {
				if (!resultSlotStack(handler).isEmpty()) {
					state = State.DECIDE_AND_TAKE;
					return;
				}
				if (++waitTicks > RmhConstants.SERVER_WAIT_TIMEOUT_TICKS) {
					skipUnusableMap(player, im, handler); // no preview: the map was probably already locked
				}
			}
			case DECIDE_AND_TAKE -> {
				if (hasLastLocked && (lastLockedId + 1) > target) {
					terminate("§6[RoundMapHunter] next lock (#" + (lastLockedId + 1)
							+ ") would pass target #" + target + " — stopping without locking.", true, player);
					return;
				}
				if (findEmptyPlayerSlot(handler) < 0) {
					terminate("§6[RoundMapHunter] inventory full — stopping.", true, player);
					return;
				}
				collectEmptyPlayerSlots(handler, emptyBeforeTake); // remember where the locked map may land
				state = State.TAKE;
			}
			case TAKE -> {
				quickMove(im, handler, CartographyTableScreenHandler.RESULT_SLOT_INDEX, player); // shift-click output
				gap();
				waitTicks = 0;
				state = State.WAIT_RESULT;
			}
			case WAIT_RESULT -> {
				int lockedId = findResolvedLockedMap(handler);
				if (lockedId >= 0) {
					onLocked(player, lockedId, target);
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
			player.playSound(SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.0f); // client-local, no packet
			chat("§a[RoundMapHunter] success! locked map #" + lockedId + " (target reached).");
			running = false;
			return;
		}
		if (lockedId > target) {
			terminate("§6[RoundMapHunter] overshot: locked #" + lockedId + " > target #" + target
					+ " (first lock could not be predicted) — stopping.", true, player);
			return;
		}
		chat("§7[RoundMapHunter] locked #" + lockedId + ", continuing toward #" + target + "…");
		cooldown = RoundMapHunterConfig.get().lockDelayTicks;
		state = State.CHECK_CURSOR;
	}

	/** Move an unusable (no-preview) map out of the map slot, remember it, and look for another. */
	private void skipUnusableMap(ClientPlayerEntity player, ClientPlayerInteractionManager im,
			CartographyTableScreenHandler handler) {
		int id = mapId(mapSlotStack(handler));
		if (id >= 0) {
			skippedMapIds.add(id);
		}
		if (findEmptyPlayerSlot(handler) < 0) {
			terminate("§6[RoundMapHunter] inventory full while skipping an unusable map — stopping.", true, player);
			return;
		}
		quickMove(im, handler, CartographyTableScreenHandler.MAP_SLOT_INDEX, player); // slot 0 -> inventory
		gap();
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
	// Slot helpers — all resolve ids/roles from the handler; nothing is hardcoded.

	private ItemStack mapSlotStack(CartographyTableScreenHandler h) {
		return h.getSlot(CartographyTableScreenHandler.MAP_SLOT_INDEX).getStack();
	}

	private ItemStack materialSlotStack(CartographyTableScreenHandler h) {
		return h.getSlot(CartographyTableScreenHandler.MATERIAL_SLOT_INDEX).getStack();
	}

	private ItemStack resultSlotStack(CartographyTableScreenHandler h) {
		return h.getSlot(CartographyTableScreenHandler.RESULT_SLOT_INDEX).getStack();
	}

	private boolean isPlayerSlot(Slot slot, PlayerInventory inv) {
		return slot.inventory == inv;
	}

	private int mapId(ItemStack stack) {
		MapIdComponent id = stack.get(DataComponentTypes.MAP_ID);
		return id == null ? -1 : id.id();
	}

	/** Scans the whole player inventory (main + hotbar) every time; never reuses a previous index. */
	private int findUnlockedFilledMapPlayerSlot(MinecraftClient client, CartographyTableScreenHandler handler) {
		PlayerInventory inv = client.player.getInventory();
		for (Slot slot : handler.slots) {
			if (!isPlayerSlot(slot, inv)) {
				continue;
			}
			ItemStack stack = slot.getStack();
			if (!stack.isOf(Items.FILLED_MAP) || !stack.contains(DataComponentTypes.MAP_ID)) {
				continue;
			}
			if (skippedMapIds.contains(mapId(stack))) {
				continue;
			}
			MapState ms = client.world == null ? null : FilledMapItem.getMapState(stack, client.world);
			if (ms == null || !ms.locked) { // confirmed unlocked, or state not synced -> try it
				return slot.id;
			}
		}
		return -1;
	}

	private int findGlassPanePlayerSlot(CartographyTableScreenHandler handler) {
		PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
		for (Slot slot : handler.slots) {
			if (isPlayerSlot(slot, inv) && slot.getStack().isOf(Items.GLASS_PANE)) {
				return slot.id;
			}
		}
		return -1;
	}

	private int findEmptyPlayerSlot(CartographyTableScreenHandler handler) {
		PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
		for (Slot slot : handler.slots) {
			if (isPlayerSlot(slot, inv) && slot.getStack().isEmpty()) {
				return slot.id;
			}
		}
		return -1;
	}

	private void collectEmptyPlayerSlots(CartographyTableScreenHandler handler, Set<Integer> out) {
		out.clear();
		PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
		for (Slot slot : handler.slots) {
			if (isPlayerSlot(slot, inv) && slot.getStack().isEmpty()) {
				out.add(slot.id);
			}
		}
	}

	/**
	 * After a shift-click take, the locked map lands in one of the slots that were empty beforehand.
	 * Returns its id once the server has replaced the predicted copy (which still carries the LOCK
	 * post-processing) with the real locked map, or -1 while still waiting.
	 */
	private int findResolvedLockedMap(CartographyTableScreenHandler handler) {
		for (Slot slot : handler.slots) {
			if (!emptyBeforeTake.contains(slot.id)) {
				continue;
			}
			ItemStack stack = slot.getStack();
			if (stack.contains(DataComponentTypes.MAP_ID) && !stack.contains(DataComponentTypes.MAP_POST_PROCESSING)) {
				return mapId(stack);
			}
		}
		return -1;
	}

	// -------------------------------------------------------------------------------------------

	/** A single shift-click on a slot — identical to a human shift-clicking it. */
	private void quickMove(ClientPlayerInteractionManager im, CartographyTableScreenHandler handler,
			int slotId, ClientPlayerEntity player) {
		im.clickSlot(handler.syncId, slotId, 0, SlotActionType.QUICK_MOVE, player);
	}

	/** Small pause between individual clicks so we never spam packets within a cycle. */
	private void gap() {
		cooldown = RmhConstants.INTER_ACTION_TICKS;
	}

	private void chat(String msg) {
		RoundMapHunterClient.sendChat(Text.literal(msg));
	}
}
