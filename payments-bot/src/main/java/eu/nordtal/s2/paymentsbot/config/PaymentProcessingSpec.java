package eu.nordtal.s2.paymentsbot.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Reload;

/**
 * {@code config/payment-processing.yml}. Replaces the Lombok-annotated
 * {@code PaymentProcessingConfig} class that jcore 1.x loaded from JSON.
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  payments-bot - payment processing",
        "-------------------------------------------------------------------",
        "Every setting here can be overridden with an environment variable",
        "named NORDTAL_PAYMENT_PROCESSING_<SETTING>, e.g.",
        "NORDTAL_PAYMENT_PROCESSING_CHECK_INTERVAL_SECONDS. The environment",
        "wins over this file and is never written back into it.",
        "",
        "A setting that does not exist stops the bot from starting rather",
        "than being deleted from this file."
})
public interface PaymentProcessingSpec {

    @Order(1)
    @Key("check-interval-seconds")
    @Comment({
            "How often the bunq account is polled for new payments, in seconds.",
            "Must be greater than zero."
    })
    default long checkIntervalSeconds() {
        return 10;
    }

    @Order(2)
    @Key("confirmation-channel-id")
    @Comment("Discord text channel that receives a message for each processed payment.")
    default String confirmationChannelId() {
        return "1397264662545957056";
    }

    @Order(3)
    @Key("balance")
    @Comment("The voice channel whose name shows the current account balance.")
    BalanceSpec balance();

    @Reload
    void reload();

    /** The balance display channel. */
    @ConfigSpec
    interface BalanceSpec {

        @Order(1)
        @Key("channel-id")
        @Comment("Discord voice channel whose name is rewritten with the balance.")
        default String channelId() {
            return "1417574134958788720";
        }

        @Order(2)
        @Key("name-format")
        @Comment({
                "How the channel name is rendered. Must contain exactly one %s,",
                "which is replaced with the balance."
        })
        default String nameFormat() {
            return "💶・nordtal's balance・%s€";
        }
    }
}
