package eu.nordtal.s2.discordbot.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Secret;

/**
 * The {@code bot} group: the Discord token, validated at startup.
 *
 * It comes from {@code NORDTAL_BOT_TOKEN}; the bunq credentials belong to {@code steward}.
 */
@ConfigSpec
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
