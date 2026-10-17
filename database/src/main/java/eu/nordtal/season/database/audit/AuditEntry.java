package eu.nordtal.season.database.audit;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messages.MessageRef;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code journal_entry} with its values, serialised straight to JSON for Steward to render.
 *
 * @param occurred when it happened, by the database's clock
 * @param action   what happened
 * @param actor    who did it: a person, Steward on its own, the host or the bot
 * @param subject  the person the entry is about, {@code null} when there is no one person
 * @param mcUuid   the Minecraft account for link and unlink, {@code null} otherwise
 * @param line     the line rendered from its values when it was read
 */
public record AuditEntry(
        UUID id,
        Instant occurred,
        JournalAction action,
        Actor actor,
        @Nullable DiscordId subject,
        @Nullable UUID mcUuid,
        MessageRef line) {}
