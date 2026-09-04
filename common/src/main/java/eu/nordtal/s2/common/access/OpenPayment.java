package eu.nordtal.s2.common.access;

import java.time.Instant;

/**
 * A purchase somebody has started and not finished.
 *
 * <h2>Why anything outside the bot reads this at all</h2>
 * Because "they have not paid" and "they are in the middle of paying" are different answers to the
 * same complaint, and an admin standing next to somebody who cannot get in is the person who needs
 * the second one. Everything here is already in the database rather than in the bot's memory -
 * season 1 kept it in a Guava cache, so a restart answered "setup expired" to everybody mid-purchase
 * - which is what makes reading it from another process possible at all.
 *
 * <p><b>Read-only, everywhere except the bot.</b> {@code payment_request} is a state machine with
 * one owner ({@code Purchases}), and a second writer is a second half-finished purchase. Nothing in
 * this package writes it, and nothing should.</p>
 *
 * @param reference      {@code NT-XXXXXX} - what the payer types, and what an admin quotes into
 *                       {@code /settle}
 * @param days           how many days of access were ordered
 * @param amountCents    what the tab asks for, in cents, donation included. Integer cents
 *                       everywhere: season 1 used {@code Float.parseFloat} and {@code <}
 * @param donationCents  the optional surcharge, {@code 0} when there is none
 * @param hasTab         whether a bunq tab exists yet. This is the real distinction the row carries
 *                       - {@code bunq_tab_id IS NULL} is exactly the difference between "chose 60
 *                       days" and "asked for a payment link", and a purchase stuck on the first is
 *                       a different problem from one stuck on the second
 * @param created        when it was started, so an admin can see whether it is stuck or fresh
 */
public record OpenPayment(String reference, int days, int amountCents, int donationCents,
                          boolean hasTab, Instant created) {

    /** The total, as a decimal string with two places - for a message, never for arithmetic. */
    public String amount() {
        return (amountCents / 100) + "." + String.format("%02d", Math.abs(amountCents % 100));
    }
}
