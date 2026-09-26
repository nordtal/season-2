package eu.nordtal.s2.common.roster;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code payment_request}, whole, for a list of every purchase there has been.
 *
 * This is not {@code eu.nordtal.s2.common.access.OpenPayment} and does not replace it. That one
 * answers "is this person in the middle of paying" for a single account and carries only what a
 * Discord message needs; this one is a row of a table somebody is scrolling through, so it carries
 * the identity ({@code id}, {@code discordId}), the money and the three timestamps.
 *
 * <b>Read-only.</b> {@code payment_request} is a state machine with one owner - the bot's
 * {@code Purchases} - and a second writer is a second half-finished purchase. Nothing in this
 * package writes it.
 *
 * {@code bunq_payment_id} is deliberately absent: it identifies a payment inside somebody's bank
 * account and is of no use to a reader who cannot open bunq, while {@code settled} already answers
 * whether it arrived.
 *
 * @param discordId     who started it; not resolved to a name here, because a name is Discord's to
 *                      answer and this is one query against one database
 * @param days          how many days of access were ordered
 * @param amountCents   what the tab asks for, in integer cents, donation included. The payer can
 *                      edit the amount on the bunq.me page, so this is what was requested and not
 *                      necessarily what arrived
 * @param donationCents the optional surcharge, {@code 0} when there is none
 * @param status        {@code OPEN}, {@code PAID}, {@code EXPIRED}, {@code CANCELLED} or
 *                      {@code SUPERSEDED}
 * @param bunqTabId     the bunq.me tab, {@code null} until a payment link was asked for - which is
 *                      exactly the difference between "chose 60 days" and "asked for a link"
 * @param shareUrl      the bunq.me URL, {@code null} for the same reason
 * @param settled       when the payment arrived; {@code null} unless {@code status} is {@code PAID},
 *                      which the schema enforces
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
