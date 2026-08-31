package eu.nordtal.s2.discordbot.discord;

import java.time.Instant;

/**
 * The end of one user's current run of access.
 * <p>
 * "The run", not "one grant": grants are appended, so what matters to a reminder is the end of the
 * whole chain. Two rows that meet in the middle are not two reminders.
 * </p>
 *
 * @param discordId  whose access it is
 * @param validUntil when it ends
 */
public record AccessDeadline(String discordId, Instant validUntil) {
}
