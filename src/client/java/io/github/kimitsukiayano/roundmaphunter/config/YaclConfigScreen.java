package io.github.kimitsukiayano.roundmaphunter.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import io.github.kimitsukiayano.roundmaphunter.RmhConstants;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Builds the YACL settings screen bound to {@link RoundMapHunterConfig}. */
public final class YaclConfigScreen {
	private YaclConfigScreen() {
	}

	public static Screen create(Screen parent) {
		RoundMapHunterConfig cfg = RoundMapHunterConfig.get();

		Option<Boolean> enabled = Option.<Boolean>createBuilder()
				.name(Text.literal("Enabled"))
				.description(OptionDescription.of(Text.literal(
						"Master on/off. Also controls whether the cartography-table button is shown.")))
				.binding(true, () -> cfg.enabled, v -> cfg.enabled = v)
				.controller(TickBoxControllerBuilder::create)
				.build();

		Option<Boolean> showAuto = Option.<Boolean>createBuilder()
				.name(Text.literal("Show AUTO button"))
				.description(OptionDescription.of(Text.literal(
						"Show the AUTO (full auto-loop) button on the cartography screen.")))
				.binding(true, () -> cfg.showAutoButton, v -> cfg.showAutoButton = v)
				.controller(TickBoxControllerBuilder::create)
				.build();

		Option<Integer> targetId = Option.<Integer>createBuilder()
				.name(Text.literal("Target map id (T)"))
				.description(OptionDescription.of(Text.literal(
						"The id you want the locked copy to end up as (e.g. 777). Must be >= 0.")))
				.binding(0, () -> cfg.targetId, v -> cfg.targetId = v)
				.controller(opt -> IntegerFieldControllerBuilder.create(opt).min(0).max(Integer.MAX_VALUE))
				.build();

		Option<Integer> delay = Option.<Integer>createBuilder()
				.name(Text.literal("Lock delay (ticks)"))
				.description(OptionDescription.of(Text.literal(
						"Ticks between lock operations. Higher is slower and safer; the minimum is clamped.")))
				.binding(RmhConstants.DEFAULT_LOCK_DELAY_TICKS, () -> cfg.lockDelayTicks, v -> cfg.lockDelayTicks = v)
				.controller(opt -> IntegerSliderControllerBuilder.create(opt)
						.range(RmhConstants.MIN_LOCK_DELAY_TICKS, RmhConstants.MAX_LOCK_DELAY_TICKS)
						.step(1))
				.build();

		return YetAnotherConfigLib.createBuilder()
				.title(Text.literal("Round-Numbered Map ID Hunter"))
				.category(ConfigCategory.createBuilder()
						.name(Text.literal("General"))
						.option(enabled)
						.option(showAuto)
						.option(targetId)
						.option(delay)
						.build())
				.save(() -> {
					cfg.sanitize();
					cfg.save();
				})
				.build()
				.generateScreen(parent);
	}
}
