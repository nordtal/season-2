package eu.nordtal.s2.discordbot.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Secret;

/**
 * {@code config/bot.yml} - the Discord token.
 *
 * Declared here so it is validated once at startup rather than surfacing as an invalid login minutes later.
 *
 * It is meant to come from the environment ({@code NORDTAL_BOT_TOKEN}): the default is empty and
 * the bot refuses to start while it is.
 *
 * The bunq credentials - {@code bunq.api-key}, {@code bunq.account-id},
 * {@code bunq.context-path} - live as {@code bunq:} in {@code steward-worker}'s {@code steward.yml}
 * instead, supplied as {@code NORDTAL_STEWARD_BUNQ_*}; this process has neither the key nor the
 * bunq SDK on its classpath. The bot asks for a payment link by writing a row and is told the
 * answer the same way.
 *
 * Nothing here reads the old {@code NORDTAL_BOT_BUNQ_*} names: jcore drops an unknown key with a
 * warning and a {@code .bak}, and a bunq that is simply absent is a valid season.
 * steward-worker's start line says so out loud, and the bot repeats it, through
 * {@code bot_setting}, in {@link Configured#report}.
 */
@ConfigSpec(
        header = {
            "-------------------------------------------------------------------",
            "  access-bot - credentials",
            "-------------------------------------------------------------------",
            "LEAVE THIS EMPTY. Supply it through the environment instead:",
            "",
            "  NORDTAL_BOT_TOKEN   the Discord bot token",
            "",
            "An environment value is never written back into this file. Anything",
            "written here does end up in the config volume, so only do that for a",
            "local checkout.",
            "",
            "The bot will not start while it is empty.",
            "",
            "The bunq key is NOT here any more. It belongs to steward-worker as",
            "NORDTAL_STEWARD_BUNQ_API_KEY / NORDTAL_STEWARD_BUNQ_ACCOUNT_ID, and",
            "that container is the only one in the network that holds it."
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
