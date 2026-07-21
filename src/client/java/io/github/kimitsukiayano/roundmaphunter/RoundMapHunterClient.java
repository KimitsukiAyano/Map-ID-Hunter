package io.github.kimitsukiayano.roundmaphunter;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. Step 1: an empty, buildable skeleton that only logs on init.
 * Real behaviour (cartography-table button, auto-lock loop, config) is added in later steps.
 */
public class RoundMapHunterClient implements ClientModInitializer {
	public static final String MOD_ID = "roundmaphunter";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		LOGGER.info("[{}] client initialized (skeleton)", MOD_ID);
	}
}
