package io.github.kimitsukiayano.roundmaphunter.autolock;

import io.github.kimitsukiayano.roundmaphunter.RmhConstants;
import io.github.kimitsukiayano.roundmaphunter.RoundMapHunterClient;
import io.github.kimitsukiayano.roundmaphunter.config.RoundMapHunterConfig;
import io.github.kimitsukiayano.roundmaphunter.mixin.client.MinecraftClientAccessor;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CartographyTableScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
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
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.HashSet;
import java.util.Set;

/**
 * AUTO button: full unattended loop that reaches the target map id by repeatedly
 * lock → discard unwanted → close → craft empty maps into completed maps → reopen → lock.
 *
 * <p><b>Aim is never touched.</b> The loop only ever uses the player's current
 * {@code crosshairTarget}; it never changes yaw/pitch and has no auto-aim. If the crosshair is not
 * on a cartography table it stops with a message.
 *
 * <p><b>N is observed, not read from the preview.</b> The 1.21.11 output preview only carries the
 * source map's id, so the next-issued id N is learned from the id of each map that is actually
 * locked/crafted. Locked ids are consecutive, so once N is known the loop predicts subsequent ids,
 * sizes each craft batch ({@code m_min = ceil((D+1)/2)}, {@code m_max = D}, capped by materials),
 * runs a fast mode away from the target and switches to per-lock verification within
 * {@code verifyThreshold}. Only the very first lock cannot be predicted.
 *
 * <p>All item moves are vanilla clicks: {@code QUICK_MOVE} (shift-click) to insert/take,
 * {@code THROW} (drop key) to discard; crafting uses {@code interactItem} (the sneak-right-click use
 * packet) so aiming at the table does not open its GUI; hotbar changes press the vanilla hotbar
 * key-binding (never writing selectedSlot directly).
 */
public final class AutoLoopController {
	public static final AutoLoopController INSTANCE = new AutoLoopController();

	private AutoLoopController() {
	}

	private enum Phase {
		LOCK_ENSURE_MAP,
		LOCK_ENSURE_GLASS,
		LOCK_WAIT_PREVIEW,
		LOCK_TAKE,
		LOCK_WAIT_RESULT,
		REPLENISH_CHECK,
		DISCARD,
		DISCARD_CONFIRM,
		PLAN_BATCH,
		CLOSE_GUI,
		SELECT_HOTBAR,
		CRAFT,
		REOPEN
	}

	private boolean running;
	private Phase phase;
	private int cooldown;
	private int waitTicks;
	private int retryCount;

	private int targetT;
	private boolean hasKnownNext;
	private int knownNext;          // id the next lock will produce (== world counter), once known
	private boolean mustVerifyNextLock; // force verification (re-anchor knownNext) on the next take

	private final Set<Integer> skippedMapIds = new HashSet<>();
	private final Set<Integer> emptyBeforeTake = new HashSet<>();
	private final Set<Integer> producedLockedIds = new HashSet<>(); // junk (<T) maps we may drop to free space

	private int batchRemaining;     // crafts left to do this batch (upper bound = m_max)
	private int assumedTakeId;      // fast-mode: id we assume the current take produced
	private int discardAttempts;    // guard against an endless discard loop

	// -------------------------------------------------------------------------------------------

	public boolean isRunning() {
		return running;
	}

	public void toggle(CartographyTableScreen screen) {
		if (running) {
			stop("§e[RoundMapHunter] AUTO stopped by user.", false, null);
		} else {
			start();
		}
	}

	private void start() {
		RoundMapHunterConfig config = RoundMapHunterConfig.get();
		if (!config.enabled || !config.showAutoButton) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (handler(client) == null) {
			return; // must be started from the cartography screen
		}
		if (aimCartography(client) == null) {
			chat("§c[RoundMapHunter] not aiming at a cartography table — AUTO not started.");
			return;
		}
		running = true;
		phase = Phase.LOCK_ENSURE_MAP;
		cooldown = 0;
		waitTicks = 0;
		retryCount = 0;
		targetT = config.targetId;
		hasKnownNext = false;
		knownNext = -1;
		mustVerifyNextLock = true; // verify the very first lock (learn N)
		skippedMapIds.clear();
		emptyBeforeTake.clear();
		producedLockedIds.clear();
		batchRemaining = 0;
		chat("§d[RoundMapHunter] AUTO started (target #" + targetT + "). First lock cannot be predicted; "
				+ "start below the target.");
	}

	private void stop(String message, boolean closeGui, ClientPlayerEntity player) {
		running = false;
		if (message != null) {
			chat(message);
		}
		if (closeGui && player != null) {
			player.closeHandledScreen();
		}
	}

	// -------------------------------------------------------------------------------------------

	public void tick(MinecraftClient client) {
		if (!running) {
			return;
		}
		ClientPlayerEntity player = client.player;
		ClientPlayerInteractionManager im = client.interactionManager;
		if (player == null || im == null) {
			running = false;
			return;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		try {
			step(client, player, im);
		} catch (Exception e) {
			RoundMapHunterClient.LOGGER.error("[autoloop] unexpected error, aborting", e);
			forceClose(player);
			stop("§c[RoundMapHunter] AUTO internal error — stopped.", false, null);
		}
	}

	private void step(MinecraftClient client, ClientPlayerEntity player, ClientPlayerInteractionManager im) {
		RoundMapHunterConfig config = RoundMapHunterConfig.get();

		switch (phase) {
			// ---------------- Locking (GUI open) ----------------
			case LOCK_ENSURE_MAP -> {
				CartographyTableScreenHandler h = requireOpen(client, player);
				if (h == null) {
					return;
				}
				if (!h.getCursorStack().isEmpty()) {
					stop("§e[RoundMapHunter] cursor is holding an item — AUTO paused (GUI left open).", false, null);
					return;
				}
				if (!mapSlot(h).isEmpty()) {
					phase = Phase.LOCK_ENSURE_GLASS;
					return;
				}
				int mapSlot = findCompletedUnlockedMap(client, h);
				if (mapSlot < 0) {
					phase = Phase.REPLENISH_CHECK; // out of completed maps: discard leftovers, then craft
					return;
				}
				quickMove(im, h, mapSlot, player);
				gap(config);
				phase = Phase.LOCK_ENSURE_GLASS;
			}
			case LOCK_ENSURE_GLASS -> {
				CartographyTableScreenHandler h = requireOpen(client, player);
				if (h == null) {
					return;
				}
				if (mapSlot(h).isEmpty()) {
					phase = Phase.LOCK_ENSURE_MAP; // map fell out; restart cycle
					return;
				}
				if (!materialSlot(h).isEmpty() && materialSlot(h).getCount() >= 1) {
					waitTicks = 0;
					phase = Phase.LOCK_WAIT_PREVIEW;
					return;
				}
				int glassSlot = findGlass(h);
				if (glassSlot < 0) {
					stop("§6[RoundMapHunter] out of glass panes — AUTO stopping.", true, player);
					return;
				}
				quickMove(im, h, glassSlot, player);
				gap(config);
				waitTicks = 0;
				phase = Phase.LOCK_WAIT_PREVIEW;
			}
			case LOCK_WAIT_PREVIEW -> {
				CartographyTableScreenHandler h = requireOpen(client, player);
				if (h == null) {
					return;
				}
				if (!resultSlot(h).isEmpty()) {
					phase = Phase.LOCK_TAKE;
					return;
				}
				if (++waitTicks > config.containerWaitTimeoutTicks) {
					// No preview: skip this map (probably already locked). With the input filter fixed this
					// should be rare — a run of these in the log means locked maps are still slipping in.
					int id = mapId(mapSlot(h));
					if (id >= 0) {
						skippedMapIds.add(id);
					}
					RoundMapHunterClient.LOGGER.info("[autoloop] no preview for map #{} — skipping (already locked?)", id);
					int dest = findEmptySlot(h);
					if (dest < 0) {
						int junk = findDroppableFilledMapSlot(client, h);
						if (junk >= 0) {
							im.clickSlot(h.syncId, junk, 1, SlotActionType.THROW, player);
							gap(config);
							return;
						}
						stop("§6[RoundMapHunter] inventory full and no map to discard — AUTO stopping.", true, player);
						return;
					}
					quickMove(im, h, CartographyTableScreenHandler.MAP_SLOT_INDEX, player);
					gap(config);
					phase = Phase.LOCK_ENSURE_MAP;
				}
			}
			case LOCK_TAKE -> {
				CartographyTableScreenHandler h = requireOpen(client, player);
				if (h == null) {
					return;
				}
				// Overshoot guard for every predictable lock.
				if (hasKnownNext && knownNext > targetT) {
					stop("§6[RoundMapHunter] next lock (#" + knownNext + ") would pass target #" + targetT
							+ " — AUTO stopping.", true, player);
					return;
				}
				if (findEmptySlot(h) < 0) {
					// Make room by dropping one map (locked first, else an unlocked completed map) instead
					// of stopping. Only abort if there is no filled map at all to drop.
					int junk = findDroppableFilledMapSlot(client, h);
					if (junk >= 0) {
						im.clickSlot(h.syncId, junk, 1, SlotActionType.THROW, player);
						gap(config);
						return; // re-evaluate once the slot frees up
					}
					stop("§6[RoundMapHunter] inventory full and no map to discard — AUTO stopping.", true, player);
					return;
				}
				boolean verify = mustVerifyNextLock || !config.fastMode || !hasKnownNext
						|| (targetT - knownNext) <= config.verifyThreshold;
				collectEmptySlots(h, emptyBeforeTake);
				assumedTakeId = knownNext;
				quickMove(im, h, CartographyTableScreenHandler.RESULT_SLOT_INDEX, player);
				gap(config);
				if (verify) {
					waitTicks = 0;
					phase = Phase.LOCK_WAIT_RESULT;
				} else {
					// Fast mode: trust the deterministic id, don't wait to read it.
					if (hasKnownNext) {
						producedLockedIds.add(assumedTakeId); // droppable junk (<T)
						knownNext++;
					}
					phase = Phase.LOCK_ENSURE_MAP;
				}
			}
			case LOCK_WAIT_RESULT -> {
				CartographyTableScreenHandler h = requireOpen(client, player);
				if (h == null) {
					return;
				}
				int locked = findResolvedLockedMap(h);
				if (locked >= 0) {
					onLocked(player, locked);
					return;
				}
				if (++waitTicks > config.containerWaitTimeoutTicks) {
					stop("§c[RoundMapHunter] timed out reading the locked id — AUTO stopping.", true, player);
				}
			}

			// ---------------- Replenish: check → discard ALL filled maps → size batch → craft ----------------
			case REPLENISH_CHECK -> {
				CartographyTableScreenHandler h = requireOpen(client, player);
				if (h == null) {
					return;
				}
				if (hasKnownNext && targetT - knownNext <= 0) {
					stop("§6[RoundMapHunter] target #" + targetT + " is no longer reachable — AUTO stopping.",
							true, player);
					return;
				}
				discardAttempts = 0;
				phase = Phase.DISCARD;
			}
			case DISCARD -> {
				// Discard EVERY completed map (locked or not), one per tick, re-scanning each time so we
				// never rely on a stale snapshot. Empty maps and glass panes are never touched.
				CartographyTableScreenHandler h = requireOpen(client, player);
				if (h == null) {
					return;
				}
				int slot = findFirstFilledMapSlot(h);
				if (slot >= 0) {
					if (discardAttempts++ > countPlayerSlots(h) * 2) {
						stop("§c[RoundMapHunter] could not discard leftover maps — AUTO stopping.", true, player);
						return;
					}
					im.clickSlot(h.syncId, slot, 1, SlotActionType.THROW, player); // = drop-stack key (Q)
					gap(config);
					return; // re-scan next tick
				}
				waitTicks = 0;
				phase = Phase.DISCARD_CONFIRM;
			}
			case DISCARD_CONFIRM -> {
				// Gate: proceed only when a re-scan AFTER a container-settle wait still shows zero completed
				// maps (guards against a client-prediction vs server-confirmation mismatch).
				CartographyTableScreenHandler h = requireOpen(client, player);
				if (h == null) {
					return;
				}
				if (++waitTicks < RmhConstants.DISCARD_CONFIRM_TICKS) {
					return;
				}
				if (findFirstFilledMapSlot(h) >= 0) {
					phase = Phase.DISCARD; // one remained / reappeared — keep discarding
					return;
				}
				skippedMapIds.clear();
				phase = Phase.PLAN_BATCH;
			}
			case PLAN_BATCH -> {
				// Size the craft batch from the POST-discard free slots (not before/during discard).
				CartographyTableScreenHandler h = requireOpen(client, player);
				if (h == null) {
					return;
				}
				int m;
				if (!hasKnownNext) {
					m = 1; // bootstrap: craft one, lock it, and learn N from the observed id
				} else {
					int d = targetT - knownNext;
					if (d <= 0) {
						stop("§6[RoundMapHunter] target #" + targetT + " is no longer reachable — AUTO stopping.",
								true, player);
						return;
					}
					int createCap = Math.max(0, countFreeSlots(h) - 1); // always keep one working slot free
					int cap = Math.min(countEmptyMaps(client), createCap);
					m = Math.min(d, cap); // m <= m_max = D keeps us from overshooting
				}
				if (m <= 0) {
					stop("§6[RoundMapHunter] not enough empty maps / free slots — AUTO stopping.", true, player);
					return;
				}
				batchRemaining = m;
				phase = Phase.CLOSE_GUI;
			}
			case CLOSE_GUI -> {
				player.closeHandledScreen();
				retryCount = 0;
				gap(config);
				phase = Phase.SELECT_HOTBAR;
			}
			case SELECT_HOTBAR -> {
				int hotbar = findEmptyMapHotbarSlot(player);
				if (hotbar < 0) {
					stop("§6[RoundMapHunter] out of empty maps — AUTO stopping.", false, null);
					return;
				}
				if (player.getInventory().getSelectedSlot() == hotbar) {
					retryCount = 0;
					phase = Phase.CRAFT;
					return;
				}
				if (retryCount++ > RmhConstants.RETRY_LIMIT) {
					stop("§c[RoundMapHunter] could not switch hotbar — AUTO stopping.", false, null);
					return;
				}
				pressHotbarKey(client, hotbar);
				cooldown = Math.max(config.minActionIntervalTicks, 1);
			}
			case CRAFT -> {
				// Recount free slots every craft and keep one working slot free (createCap); never rely on
				// a fixed count. batchRemaining still caps us at m_max so we cannot overshoot. On any stop
				// condition, go lock whatever we made — do NOT terminate here.
				int createCap = Math.max(0, countFreeSlotsInventory(player) - 1);
				if (batchRemaining <= 0 || createCap <= 0) {
					phase = Phase.REOPEN;
					retryCount = 0;
					return;
				}
				ItemStack held = player.getInventory().getSelectedStack();
				if (!held.isOf(Items.MAP)) {
					if (findEmptyMapHotbarSlot(player) < 0) {
						phase = Phase.REOPEN; // out of empty maps — lock what we made
						retryCount = 0;
						return;
					}
					phase = Phase.SELECT_HOTBAR;
					return;
				}
				MinecraftClientAccessor acc = (MinecraftClientAccessor) client;
				if (acc.roundmaphunter$getItemUseCooldown() > 0) {
					return; // respect the vanilla item-use cooldown; never shortened
				}
				im.interactItem(player, Hand.MAIN_HAND); // fill one empty map (sneak-use packet, no GUI)
				acc.roundmaphunter$setItemUseCooldown(RmhConstants.VANILLA_ITEM_USE_COOLDOWN_TICKS);
				if (hasKnownNext) {
					knownNext++; // filling advances the world counter by one
				}
				batchRemaining--;
			}
			case REOPEN -> {
				BlockHitResult hit = aimCartography(client);
				if (hit == null) {
					stop("§c[RoundMapHunter] not aiming at a cartography table — AUTO stopping.", false, null);
					return;
				}
				if (handler(client) != null) {
					retryCount = 0;
					mustVerifyNextLock = true; // re-anchor knownNext to the true counter after crafting
					phase = Phase.LOCK_ENSURE_MAP; // reopened successfully
					return;
				}
				MinecraftClientAccessor acc = (MinecraftClientAccessor) client;
				if (acc.roundmaphunter$getItemUseCooldown() > 0) {
					return; // respect the vanilla item-use cooldown between placement attempts
				}
				if (retryCount++ > RmhConstants.RETRY_LIMIT) {
					stop("§c[RoundMapHunter] could not reopen the cartography table (are you sneaking?) — "
							+ "AUTO stopping.", false, null);
					return;
				}
				im.interactBlock(player, Hand.MAIN_HAND, hit);
				acc.roundmaphunter$setItemUseCooldown(RmhConstants.VANILLA_ITEM_USE_COOLDOWN_TICKS);
			}
			default -> running = false;
		}
	}

	// -------------------------------------------------------------------------------------------

	private void onLocked(ClientPlayerEntity player, int lockedId) {
		// Decisions are made on the ACTUAL locked output id read from the result, never on prediction.
		knownNext = lockedId + 1; // re-anchor to the true counter
		hasKnownNext = true;
		mustVerifyNextLock = false;
		if (lockedId == targetT) {
			player.playSound(SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.0f);
			chat("§a[RoundMapHunter] success! locked map #" + lockedId + " (target reached).");
			running = false; // keep GUI open
			return;
		}
		if (lockedId > targetT) {
			stop("§6[RoundMapHunter] overshot: locked #" + lockedId + " > target #" + targetT
					+ " (first lock could not be predicted) — AUTO stopping.", true, player);
			return;
		}
		producedLockedIds.add(lockedId); // droppable junk (<T)
		RoundMapHunterConfig config = RoundMapHunterConfig.get();
		gap(config);
		phase = Phase.LOCK_ENSURE_MAP;
	}

	// -------------------------------------------------------------------------------------------
	// Cartography / crosshair helpers

	private CartographyTableScreenHandler handler(MinecraftClient client) {
		if (client.currentScreen instanceof CartographyTableScreen screen) {
			return screen.getScreenHandler();
		}
		return null;
	}

	private CartographyTableScreenHandler requireOpen(MinecraftClient client, ClientPlayerEntity player) {
		CartographyTableScreenHandler h = handler(client);
		if (h == null) {
			stop("§e[RoundMapHunter] cartography screen closed — AUTO stopping.", false, null);
		}
		return h;
	}

	private BlockHitResult aimCartography(MinecraftClient client) {
		if (client.crosshairTarget instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK
				&& client.world != null
				&& client.world.getBlockState(hit.getBlockPos()).isOf(Blocks.CARTOGRAPHY_TABLE)) {
			return hit;
		}
		return null;
	}

	private void forceClose(ClientPlayerEntity player) {
		if (player != null && MinecraftClient.getInstance().currentScreen instanceof CartographyTableScreen) {
			player.closeHandledScreen();
		}
	}

	// ---- slot helpers (GUI open) ----

	private ItemStack mapSlot(CartographyTableScreenHandler h) {
		return h.getSlot(CartographyTableScreenHandler.MAP_SLOT_INDEX).getStack();
	}

	private ItemStack materialSlot(CartographyTableScreenHandler h) {
		return h.getSlot(CartographyTableScreenHandler.MATERIAL_SLOT_INDEX).getStack();
	}

	private ItemStack resultSlot(CartographyTableScreenHandler h) {
		return h.getSlot(CartographyTableScreenHandler.RESULT_SLOT_INDEX).getStack();
	}

	private boolean isPlayerSlot(Slot slot, PlayerInventory inv) {
		return slot.inventory == inv;
	}

	private int mapId(ItemStack stack) {
		MapIdComponent id = stack.get(DataComponentTypes.MAP_ID);
		return id == null ? -1 : id.id();
	}

	private int findCompletedUnlockedMap(MinecraftClient client, CartographyTableScreenHandler h) {
		PlayerInventory inv = client.player.getInventory();
		for (Slot slot : h.slots) {
			if (!isPlayerSlot(slot, inv)) {
				continue;
			}
			ItemStack stack = slot.getStack();
			if (!stack.isOf(Items.FILLED_MAP) || !stack.contains(DataComponentTypes.MAP_ID)) {
				continue;
			}
			int id = mapId(stack);
			if (skippedMapIds.contains(id)) {
				continue;
			}
			// Never feed a locked map into the input slot: it can't be re-locked and just wastes a round
			// trip. Exclude maps we locked ourselves (covers the window before MapState syncs) and any
			// map the client confirms is locked.
			if (producedLockedIds.contains(id) || isLocked(client, stack)) {
				continue;
			}
			return slot.id;
		}
		return -1;
	}

	/** True only when the client can confirm the map is locked (MapState synced and locked). */
	private boolean isLocked(MinecraftClient client, ItemStack stack) {
		if (client.world == null) {
			return false;
		}
		MapState ms = FilledMapItem.getMapState(stack, client.world);
		return ms != null && ms.locked;
	}

	private int findGlass(CartographyTableScreenHandler h) {
		PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
		for (Slot slot : h.slots) {
			if (isPlayerSlot(slot, inv) && slot.getStack().isOf(Items.GLASS_PANE)) {
				return slot.id;
			}
		}
		return -1;
	}

	private int findEmptySlot(CartographyTableScreenHandler h) {
		PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
		for (Slot slot : h.slots) {
			if (isPlayerSlot(slot, inv) && slot.getStack().isEmpty()) {
				return slot.id;
			}
		}
		return -1;
	}

	private void collectEmptySlots(CartographyTableScreenHandler h, Set<Integer> out) {
		out.clear();
		PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
		for (Slot slot : h.slots) {
			if (isPlayerSlot(slot, inv) && slot.getStack().isEmpty()) {
				out.add(slot.id);
			}
		}
	}

	private int findResolvedLockedMap(CartographyTableScreenHandler h) {
		for (Slot slot : h.slots) {
			if (!emptyBeforeTake.contains(slot.id)) {
				continue;
			}
			ItemStack stack = slot.getStack();
			if (stack.contains(DataComponentTypes.MAP_ID)
					&& !stack.contains(DataComponentTypes.MAP_POST_PROCESSING)) {
				return mapId(stack);
			}
		}
		return -1;
	}

	private int findFirstFilledMapSlot(CartographyTableScreenHandler h) {
		PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
		for (Slot slot : h.slots) {
			if (isPlayerSlot(slot, inv) && slot.getStack().isOf(Items.FILLED_MAP)) {
				return slot.id;
			}
		}
		return -1;
	}

	private int countPlayerSlots(CartographyTableScreenHandler h) {
		PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
		int n = 0;
		for (Slot slot : h.slots) {
			if (isPlayerSlot(slot, inv)) {
				n++;
			}
		}
		return n;
	}

	/**
	 * A filled map we may drop to free a slot, by priority: (1) a locked map, else (2) an unlocked
	 * completed map. Empty maps and glass panes are never returned.
	 */
	private int findDroppableFilledMapSlot(MinecraftClient client, CartographyTableScreenHandler h) {
		PlayerInventory inv = client.player.getInventory();
		int unlockedFallback = -1;
		for (Slot slot : h.slots) {
			if (!isPlayerSlot(slot, inv)) {
				continue;
			}
			ItemStack stack = slot.getStack();
			if (!stack.isOf(Items.FILLED_MAP)) {
				continue;
			}
			if (producedLockedIds.contains(mapId(stack)) || isLocked(client, stack)) {
				return slot.id; // priority 1: a locked map
			}
			if (unlockedFallback < 0) {
				unlockedFallback = slot.id; // priority 2: an unlocked completed map
			}
		}
		return unlockedFallback;
	}

	private int countFreeSlotsInventory(ClientPlayerEntity player) {
		int n = 0;
		for (ItemStack stack : player.getInventory().getMainStacks()) {
			if (stack.isEmpty()) {
				n++;
			}
		}
		return n;
	}

	private int countEmptyMaps(MinecraftClient client) {
		int n = 0;
		for (ItemStack stack : client.player.getInventory().getMainStacks()) {
			if (stack.isOf(Items.MAP)) {
				n += stack.getCount();
			}
		}
		return n;
	}

	private int countFreeSlots(CartographyTableScreenHandler h) {
		int n = 0;
		PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
		for (Slot slot : h.slots) {
			if (isPlayerSlot(slot, inv) && slot.getStack().isEmpty()) {
				n++;
			}
		}
		return n;
	}

	// ---- crafting helpers (GUI closed) ----

	private int findEmptyMapHotbarSlot(ClientPlayerEntity player) {
		var main = player.getInventory().getMainStacks();
		for (int i = 0; i < 9 && i < main.size(); i++) {
			if (main.get(i).isOf(Items.MAP)) {
				return i;
			}
		}
		return -1;
	}

	private void pressHotbarKey(MinecraftClient client, int hotbar) {
		KeyBinding key = client.options.hotbarKeys[hotbar];
		KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(key.getBoundKeyTranslationKey()));
	}

	// -------------------------------------------------------------------------------------------

	private void quickMove(ClientPlayerInteractionManager im, CartographyTableScreenHandler h,
			int slotId, ClientPlayerEntity player) {
		im.clickSlot(h.syncId, slotId, 0, SlotActionType.QUICK_MOVE, player);
	}

	private void gap(RoundMapHunterConfig config) {
		cooldown = config.minActionIntervalTicks;
	}

	private void chat(String msg) {
		RoundMapHunterClient.sendChat(Text.literal(msg));
	}
}
