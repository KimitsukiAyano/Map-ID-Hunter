package io.github.kimitsukiayano.roundmaphunter;

import io.github.kimitsukiayano.roundmaphunter.autolock.AutoLockController;
import io.github.kimitsukiayano.roundmaphunter.autolock.AutoLoopController;
import io.github.kimitsukiayano.roundmaphunter.config.RoundMapHunterConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint.
 *
 * <p>Step 2: loads config and wires the cartography-table button (see
 * {@code mixin.client.CartographyTableScreenMixin}). The button currently only prints
 * to chat and logs the slot indices it resolved from the screen handler.
 */
public class RoundMapHunterClient implements ClientModInitializer {
	public static final String MOD_ID = "roundmaphunter";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		RoundMapHunterConfig.load();
		ClientTickEvents.END_CLIENT_TICK.register(AutoLockController.INSTANCE::tick);
		ClientTickEvents.END_CLIENT_TICK.register(AutoLoopController.INSTANCE::tick);
		LOGGER.info("[{}] client initialized (enabled={})", MOD_ID, RoundMapHunterConfig.get().enabled);
	}

	/** Append a client-local chat line. Never sent to the server. */
	public static void sendChat(Text text) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.inGameHud != null) {
			client.inGameHud.getChatHud().addMessage(text);
		}
	}
}
