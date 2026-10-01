package eu.nordtal.s2.discordbot.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Secret;

/**
 * {@code config/bot.yml}: the Discord token, validated at startup.
 *
 * It comes from {@code NORDTAL_BOT_TOKEN}; the bunq credentials belong to {@code steward}.
 */
@ConfigSpec(
        header = {
            "-------------------------------------------------------------------",
            "  access-bot: credentials",
            "-------------------------------------------------------------------",
            "LEAVE THIS EMPTY. Supply it through the environment instead:",
            "",
            "  NORDTAL_BOT_TOKEN   the Discord bot token",
            "",
            "An environment value is never written back into this file. Anything",
            "written here does end up in the config volume, so only do that for a",
            "local checkout.",
            "",
            "The bot will not start while it is empty. The bunq key belongs to",
            "steward, as NORDTAL_STEWARD_BUNQ_API_KEY."
        })
public interface BotSpec {

    @Order(1)
    @Name("Bot token")
    @Key("token")
    @Comment("Discord bot token. Set NORDTAL_BOT_TOKEN instead of filling this in.")
    @Secret
    @NoExplanationNeeded
    default String token() {
        return "";
    }
}
