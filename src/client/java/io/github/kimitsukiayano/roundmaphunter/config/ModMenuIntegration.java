package io.github.kimitsukiayano.roundmaphunter.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Registers the YACL config screen with ModMenu ("modmenu" entrypoint). */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return YaclConfigScreen::create;
	}
}
