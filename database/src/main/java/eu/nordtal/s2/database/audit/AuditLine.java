package eu.nordtal.s2.database.audit;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One journal line before it is written, for a caller that hands it to the statement performing its action.
 * Everyone else calls {@link AuditDirectory#record} directly.
 *
 * @param action  a short upper-case constant, e.g. {@code COMMAND}
 * @param actor   who did it, as a person reads it; may be null for the system itself
 * @param subject what it was about; may be null
 * @param mcUuid  the Minecraft account it concerned, where there is one; may be null
 * @param detail  one line of what happened; may be null
 */
public record AuditLine(
        String action,
        @Nullable String actor,
        @Nullable String subject,
        @Nullable UUID mcUuid,
        @Nullable String detail) {

    public AuditLine {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("a journal line without an action is not one");
        }
    }
}
