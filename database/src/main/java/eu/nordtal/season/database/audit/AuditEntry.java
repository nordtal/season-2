package eu.nordtal.season.database.audit;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messages.MessageRef;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One line of {@code audit_log}, append-only, serialised straight to JSON for Steward to render.
 *
 * @param occurred when it happened, by the database's clock
 * @param action   a {@link JournalAction}'s name; a string, since an older build's row may name one no longer listed
 * @param actor    who did it: a person, Steward on its own, or the host
 * @param subject  the person the line is about, {@code null} when there is no one person
 * @param mcUuid   the Minecraft account for link and unlink, {@code null} otherwise
 * @param line     the line as a message, its values typed, as it was written
 */
public record AuditEntry(
        UUID id,
        Instant occurred,
        String action,
        Actor actor,
        @Nullable DiscordId subject,
        @Nullable UUID mcUuid,
        MessageRef line) {}
