package eu.nordtal.s2.database.audit;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.messages.MessageRef;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One journal line before it is written: what happened, who did it, whom it concerns and the line itself.
 * The line is a message of {@link eu.nordtal.s2.database.AdminTexts}, its values typed; each reader renders it.
 *
 * @param action  what the journal files it under and filters by
 * @param actor   who did it: a person, Steward on its own, or the host
 * @param subject the person it concerns, where there is one
 * @param mcUuid  the Minecraft account it concerned, where there is one
 * @param line    the line, such as {@code TEXTS.journal().grantAccess(30, until)}
 */
public record AuditLine(
        JournalAction action,
        Actor actor,
        @Nullable DiscordId subject,
        @Nullable UUID mcUuid,
        MessageRef line) {

    public AuditLine {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(line, "line");
    }

    /** Returns a line about nobody in particular. */
    public static AuditLine of(final JournalAction action, final Actor actor, final MessageRef line) {
        return new AuditLine(action, actor, null, null, line);
    }

    /** Returns a line about one person. */
    public static AuditLine about(
            final JournalAction action, final Actor actor, final DiscordId subject, final MessageRef line) {
        return new AuditLine(action, actor, Objects.requireNonNull(subject, "subject"), null, line);
    }
}
