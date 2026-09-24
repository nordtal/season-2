package eu.nordtal.s2.discordbot.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Secret;

/**
 * {@code config/bot.yml} - the Discord token, declared here so it is validated once at startup
 * rather than surfacing as an invalid login minutes later.
 *
 * <p>It is still meant to come from the environment ({@code NORDTAL_BOT_TOKEN}): the default is
 * empty and the bot refuses to start while it is.</p>
 *
 * <h2>The bunq credentials used to be here (steward/109)</h2>
 * {@code bunq.api-key}, {@code bunq.account-id} and {@code bunq.context-path} lived in this file
 * until the bank moved into {@code steward-worker}. They are now {@code bunq:} in that container's
 * {@code steward.yml}, supplied as {@code NORDTAL_STEWARD_BUNQ_*}, and this process has neither the
 * key nor the bunq SDK on its classpath. The bot asks for a payment link by writing a row and is
 * told the answer the same way.
 *
 * <p>Nothing here reads the old names any more, which is deliberate and is also the reason
 * steward/101 exists: an environment file that still says {@code NORDTAL_BOT_BUNQ_*} is not an
 * error anybody will see, because jcore drops an unknown key with a warning and a {@code .bak}, and
 * a bunq that is simply absent is a valid season. The one thing that does say so out loud is
 * steward-worker's start line - and the bot repeats it, through {@code bot_setting}, in
 * {@link Configured#report}.</p>
 */
@ConfigSpec(header = {
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
