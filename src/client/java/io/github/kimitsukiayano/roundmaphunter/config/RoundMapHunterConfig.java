package io.github.kimitsukiayano.roundmaphunter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.kimitsukiayano.roundmaphunter.RmhConstants;
import io.github.kimitsukiayano.roundmaphunter.RoundMapHunterClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plain-data config, persisted as JSON in the Fabric config dir
 * ({@code config/roundmaphunter.json}). The YACL/ModMenu GUI in step 6 edits this
 * same object; the fields below are the JSON keys.
 */
public class RoundMapHunterConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("roundmaphunter.json");

	private static RoundMapHunterConfig instance;

	/** Master on/off. Also gates whether the cartography widgets are shown. */
	public boolean enabled = true;
	/** Show the AUTO (full auto-loop) button. Independent of {@link #enabled}. */
	public boolean showAutoButton = true;
	/** Target map id T (must be &gt;= 0). */
	public int targetId = 0;
	/** Ticks to wait between consecutive lock operations (RUN button). */
	public int lockDelayTicks = RmhConstants.DEFAULT_LOCK_DELAY_TICKS;

	// ---- AUTO loop tuning ----
	/** AUTO: skip per-lock id verification until close to the target (assumes no other id consumers). */
	public boolean fastMode = true;
	/** AUTO: start verifying every lock once within this many locks of the target. */
	public int verifyThreshold = RmhConstants.DEFAULT_VERIFY_THRESHOLD;
	/** AUTO: max ticks to wait for a server container update before giving up. */
	public int containerWaitTimeoutTicks = RmhConstants.DEFAULT_CONTAINER_TIMEOUT_TICKS;
	/** AUTO: minimum ticks between actions (floor, so we never spam). */
	public int minActionIntervalTicks = RmhConstants.DEFAULT_MIN_ACTION_INTERVAL_TICKS;

	public static RoundMapHunterConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/** Clamp values into their valid ranges. */
	public void sanitize() {
		if (targetId < 0) {
			targetId = 0;
		}
		if (lockDelayTicks < RmhConstants.MIN_LOCK_DELAY_TICKS) {
			lockDelayTicks = RmhConstants.MIN_LOCK_DELAY_TICKS;
		}
		if (lockDelayTicks > RmhConstants.MAX_LOCK_DELAY_TICKS) {
			lockDelayTicks = RmhConstants.MAX_LOCK_DELAY_TICKS;
		}
		if (verifyThreshold < RmhConstants.MIN_VERIFY_THRESHOLD) {
			verifyThreshold = RmhConstants.MIN_VERIFY_THRESHOLD;
		}
		containerWaitTimeoutTicks = Math.max(RmhConstants.MIN_CONTAINER_TIMEOUT_TICKS,
				Math.min(RmhConstants.MAX_CONTAINER_TIMEOUT_TICKS, containerWaitTimeoutTicks));
		minActionIntervalTicks = Math.max(RmhConstants.MIN_ACTION_INTERVAL_TICKS,
				Math.min(RmhConstants.MAX_ACTION_INTERVAL_TICKS, minActionIntervalTicks));
	}

	public static RoundMapHunterConfig load() {
		RoundMapHunterConfig cfg = null;
		try {
			if (Files.exists(PATH)) {
				cfg = GSON.fromJson(Files.readString(PATH), RoundMapHunterConfig.class);
			}
		} catch (Exception e) {
			RoundMapHunterClient.LOGGER.warn("[config] failed to read {}, using defaults", PATH, e);
		}
		if (cfg == null) {
			cfg = new RoundMapHunterConfig();
		}
		cfg.sanitize();
		instance = cfg;
		return cfg;
	}

	public void save() {
		sanitize();
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this));
		} catch (IOException e) {
			RoundMapHunterClient.LOGGER.warn("[config] failed to write {}", PATH, e);
		}
	}
}
