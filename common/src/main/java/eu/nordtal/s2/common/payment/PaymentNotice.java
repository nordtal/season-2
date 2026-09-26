package eu.nordtal.s2.common.payment;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Money that reached bunq and could not be booked, waiting for the bot to post it once.
 *
 * @param bunqPaymentId the payment, and the primary key, so one payment raises one notice
 * @param reason        a short label such as {@code UNMATCHED} or {@code BELOW_MINIMUM}
 * @param detail        the sentence a human reads
 */
public record PaymentNotice(
        long bunqPaymentId, String reason, @Nullable String detail, Instant reported) {}
