package eu.nordtal.season.database.audit;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messages.MessageRef;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One journal line before it is written: what happened, who did it, whom it concerns and the line itself.
 * The line is a message of {@link eu.nordtal.season.database.AdminTexts}, its values typed; each reader renders it.
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

    /** Returns the payment reference the line names, its message's {@code reference}, which the journal filters by. */
    public @Nullable String reference() {
        return line.args().get("reference") instanceof final String reference ? reference : null;
    }

    /** Returns the days the line grants or books, its message's {@code days}, which the journal filters by. */
    public @Nullable Integer days() {
        return line.args().get("days") instanceof final Integer days ? days : null;
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
