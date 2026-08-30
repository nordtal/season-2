package eu.nordtal.s2.paymentsbot.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/bot.yml} - the Discord token and the bunq credentials.
 * <p>
 * These used to be read with bare {@code System.getenv} calls scattered through the code, with no
 * check that they were set: a missing {@code BUNQ_ACCOUNT_ID} surfaced as a
 * {@code NumberFormatException} inside the poll loop, long after startup. They are declared here
 * so they are validated once, at startup, like everything else.
 * <p>
 * <b>They are still meant to come from the environment.</b> The defaults are empty and the bot
 * refuses to start while they are, so writing a real token into this file is a choice, not the
 * default path.
 * <p>
 * <b>Deploy change:</b> the variables are now {@code NORDTAL_BOT_TOKEN},
 * {@code NORDTAL_BOT_BUNQ_API_KEY} and {@code NORDTAL_BOT_BUNQ_ACCOUNT_ID}, replacing
 * {@code BOT_TOKEN}, {@code BUNQ_API_KEY} and {@code BUNQ_ACCOUNT_ID}.
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  payments-bot - credentials",
        "-------------------------------------------------------------------",
        "LEAVE THESE EMPTY. Supply them through the environment instead:",
        "",
        "  NORDTAL_BOT_TOKEN             the Discord bot token",
        "  NORDTAL_BOT_BUNQ_API_KEY      the bunq API key",
        "  NORDTAL_BOT_BUNQ_ACCOUNT_ID   the bunq monetary account id",
        "",
        "An environment value is never written back into this file. Anything",
        "written here does end up in the config volume, so only do that for a",
        "local checkout.",
        "",
        "The bot will not start while any of them is empty."
})
public interface BotSpec {

    @Order(1)
    @Key("token")
    @Comment("Discord bot token. Set NORDTAL_BOT_TOKEN instead of filling this in.")
    default String token() {
        return "";
    }

    @Order(2)
    @Key("bunq")
    @Comment("bunq API access.")
    BunqSpec bunq();

    /** bunq credentials and the API context location. */
    @ConfigSpec
    interface BunqSpec {

        @Order(1)
        @Key("api-key")
        @Comment("bunq API key. Set NORDTAL_BOT_BUNQ_API_KEY instead of filling this in.")
        default String apiKey() {
            return "";
        }

        @Order(2)
        @Key("account-id")
        @Comment({
                "The bunq monetary account id that is polled and billed.",
                "A number. The bot will not start if it is empty or not numeric."
        })
        default String accountId() {
            return "";
        }

        @Order(3)
        @Key("context-path")
        @Comment({
                "Where the bunq API context file is kept. It holds credentials and lives in a",
                "Docker-managed volume, never on the host filesystem.",
                "Empty means the working directory."
        })
        default String contextPath() {
            return "";
        }
    }
}
