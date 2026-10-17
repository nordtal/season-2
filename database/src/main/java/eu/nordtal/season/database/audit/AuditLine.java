package eu.nordtal.season.database.audit;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messages.MessageRef;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One journal entry before it is written: what happened, who did it, whom it concerns and its typed values.
 *
 * @param action  what the journal files it under and filters by
 * @param actor   who did it: a person, Steward on its own, the host or the bot
 * @param subject the person it concerns, where there is one
 * @param mcUuid  the Minecraft account it concerned, where there is one
 * @param detail  its values, such as {@code new AccessGranted(30, until)}; {@code null} for an action with none
 */
public record AuditLine(
        JournalAction action,
        Actor actor,
        @Nullable DiscordId subject,
        @Nullable UUID mcUuid,
        @Nullable JournalDetail detail) {

    public AuditLine {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
    }

    /** Returns the line this entry reads as, rendered from its values. */
    public MessageRef line() {
        return JournalDetail.line(action, detail);
    }

    /** Returns an entry about nobody in particular. */
    public static AuditLine of(final JournalAction action, final Actor actor, final @Nullable JournalDetail detail) {
        return new AuditLine(action, actor, null, null, detail);
    }

    /** Returns an entry about one person. */
    public static AuditLine about(
            final JournalAction action,
            final Actor actor,
            final DiscordId subject,
            final @Nullable JournalDetail detail) {
        return new AuditLine(action, actor, Objects.requireNonNull(subject, "subject"), null, detail);
    }
}
