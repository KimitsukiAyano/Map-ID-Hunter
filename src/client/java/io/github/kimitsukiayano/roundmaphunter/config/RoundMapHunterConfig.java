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

	/** Master on/off. Also gates whether the cartography button is shown. */
	public boolean enabled = true;
	/** Target map id T (must be &gt;= 0). */
	public int targetId = 0;
	/** Ticks to wait between consecutive lock operations. */
	public int lockDelayTicks = RmhConstants.DEFAULT_LOCK_DELAY_TICKS;

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
