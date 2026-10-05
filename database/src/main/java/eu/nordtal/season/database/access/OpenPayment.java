package eu.nordtal.season.database.access;

import java.time.Instant;

/**
 * A purchase somebody has started and not finished, read-only everywhere except the bot.
 *
 * @param reference {@code NT-XXXXXX}, what the payer types and what an admin books by hand
 * @param days how many days of access were ordered
 * @param amountCents what the tab asks for, in cents, donation included
 * @param donationCents the optional surcharge, {@code 0} when there is none
 * @param hasTab whether a bunq tab exists yet, which tells "chose the days" from "asked for a payment link"
 * @param created when it was started, so an admin can see whether it is stuck or fresh
 */
public record OpenPayment(
        String reference, int days, int amountCents, int donationCents, boolean hasTab, Instant created) {}
