package eu.nordtal.season.database.access;

import java.time.Instant;

/**
 * A purchase somebody started and did not finish. Only the bot writes it.
 *
 * @param reference {@code NT-XXXXXX}, which the payer types and an admin books by hand
 * @param amountCents the amount the tab asks for, donation included
 * @param donationCents the optional surcharge, 0 if none
 * @param hasTab whether a bunq tab exists, which tells "chose the days" from "asked for a payment link"
 */
public record OpenPayment(
        String reference, int days, int amountCents, int donationCents, boolean hasTab, Instant created) {}
