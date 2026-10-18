package eu.nordtal.season.database.audit;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messages.MessageRef;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code journal_entry} with its values, serialised to JSON for Steward.
 *
 * @param occurred the database's time of the entry
 * @param actor who acted: a person, Steward, the host or the bot
 * @param subject the person it is about, null if none
 * @param mcUuid the Minecraft account of a link or unlink, null otherwise
 * @param line the line rendered from its values when it was read
 */
public record AuditEntry(
        UUID id,
        Instant occurred,
        JournalAction action,
        Actor actor,
        @Nullable DiscordId subject,
        @Nullable UUID mcUuid,
        MessageRef line) {}
