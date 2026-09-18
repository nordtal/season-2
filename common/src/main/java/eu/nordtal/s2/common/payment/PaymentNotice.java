package eu.nordtal.s2.common.payment;

import java.time.Instant;

/**
 * One row of {@code payment_notice}: money that reached the bunq account and that nothing could
 * book on its own, waiting to be said out loud in the admin channel.
 *
 * <h2>Why it is a row and not a log line</h2>
 * The process that finds it and the process that can report it are not the same one since
 * steward/109: steward-worker is the only thing that talks to bunq, and the bot is the only thing
 * that talks to Discord. So the finding is written down, the bot claims it and posts it, and
 * {@code posted} is what makes that happen exactly once across a restart of either.
 *
 * @param bunqPaymentId the payment this is about; the primary key, which is what makes one payment
 *                      raise one notice however many polls see it
 * @param reason        {@code UNMATCHED}, {@code EXPIRED_REFERENCE}, {@code BELOW_MINIMUM} - a
 *                      short label, not a sentence
 * @param detail        the sentence a human reads, already written by whoever found it
 * @param reported      when it was written down
 */
public record PaymentNotice(long bunqPaymentId, String reason, String detail, Instant reported) {
}
