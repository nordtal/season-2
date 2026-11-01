package eu.nordtal.season.hungergames.config;

import eu.nordtal.season.database.setting.SettingRule;
import eu.nordtal.season.papercommon.game.GameKeys;
import eu.nordtal.season.settings.SettingRefused;

/** Checks the {@code config} group for what needs the game. The spec declares the rest. */
public final class HungerGamesCheck {

    private HungerGamesCheck() {}

    /** Throws a {@link SettingRefused} that names the first item the game does not know. */
    public static void check(final HungerGamesSpec config) {
        for (final HungerGamesSpec.RefillTierSpec tier : config.refillTiers()) {
            for (final String item : tier.items()) {
                if (GameKeys.material(item).isEmpty()) {
                    throw new SettingRefused(
                            "refill-tiers.items",
                            SettingRule.KNOWN,
                            item,
                            "refill-tiers: '" + item + "' is not a known material (tier at " + tier.delayMinutes()
                                    + " minutes)");
                }
            }
        }
    }
}
