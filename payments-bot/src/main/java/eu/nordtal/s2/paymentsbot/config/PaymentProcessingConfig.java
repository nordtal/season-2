package eu.nordtal.s2.paymentsbot.config;

import eu.nordtal.jcore.config.JsonConfig;
import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for the {@link eu.nordtal.s2.paymentsbot.service.PaymentProcessingService}.
 */
@Getter
@Setter
public class PaymentProcessingConfig extends JsonConfig {

    /**
     * Interval in seconds in which the bunq account is polled for new payments.
     */
    private long checkIntervalSeconds = 10;

    /**
     * Discord channel ID where confirmation messages about processed payments should be posted.
     */
    private String confirmationChannelId = "1397264662545957056";

    private String balanceChannelId = "1417574134958788720";

    private String balanceChannelFormat = "💶・nordtal's balance・%s€";
}

