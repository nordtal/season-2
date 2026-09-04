package eu.nordtal.s2.common.command;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A request one process has claimed and is about to run.
 *
 * <p>Read-only. The row is written once by the asker and settled once by the target; nothing amends
 * it in between, which is what makes {@link CommandRequests#claim} safe to be a single statement.</p>
 *
 * @param id          the row, for settling it afterwards
 * @param command     the command path joined with spaces, no leading slash
 * @param arguments   the arguments as a line, decoded against the command's own declaration
 * @param source      where it was typed
 * @param requestedBy who asked, for people to read
 * @param discordId   their Discord id, absent for the console
 * @param minecraftId their Minecraft UUID, absent for the console and for an unlinked member
 * @param locale      the language tag the answer has to be rendered in
 * @param expires     when the asker stops waiting - already in the future, or this row would not
 *                    have been claimable
 */
public record CommandRequest(long id, String command, String arguments, String source,
                             String requestedBy, Optional<String> discordId,
                             Optional<UUID> minecraftId, String locale, Instant expires) {
}
