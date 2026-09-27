package eu.nordtal.s2.common.roster;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code payment_request}, read-only, for a list of every purchase.
 *
 * @param discordId     who started it
 * @param days          how many days of access were ordered
 * @param amountCents   what the tab asks for in cents, donation included; the payer may change it on bunq.me
 * @param donationCents the optional surcharge, {@code 0} when there is none
 * @param status        {@code OPEN}, {@code PAID}, {@code EXPIRED}, {@code CANCELLED} or {@code SUPERSEDED}
 * @param bunqTabId     the bunq.me tab, {@code null} until a payment link was asked for
 * @param shareUrl      the bunq.me URL, {@code null} until a payment link was asked for
 * @param settled       when the payment arrived; {@code null} unless {@code status} is {@code PAID}
 */
public record Payment(
        UUID id,
        String reference,
        String discordId,
        int days,
        int amountCents,
        int donationCents,
        String status,
        @Nullable Long bunqTabId,
        @Nullable String shareUrl,
        Instant created,
        Instant expires,
        @Nullable Instant settled) {}
