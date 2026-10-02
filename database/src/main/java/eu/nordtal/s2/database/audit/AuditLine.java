package eu.nordtal.s2.database.audit;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Actor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One journal line before it is written: what happened, who did it, whom it concerns and its typed values.
 * Steward renders the line; nothing here is a sentence.
 *
 * @param action  a short upper-case constant, e.g. {@code GRANT_ACCESS}
 * @param actor   who did it: a person, Steward on its own, or the host
 * @param subject the person it concerns, where there is one
 * @param mcUuid  the Minecraft account it concerned, where there is one
 * @param facts   the values that say what happened, by key: ids, numbers, flags, instants and names, never prose
 */
public record AuditLine(
        String action,
        Actor actor,
        @Nullable DiscordId subject,
        @Nullable UUID mcUuid,
        Map<String, Object> facts) {

    public AuditLine {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("a journal line without an action is not one");
        }
        Objects.requireNonNull(actor, "actor");
        facts = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(facts, "facts")));
    }

    /** Returns a line about nobody in particular. */
    public static AuditLine of(final String action, final Actor actor, final Map<String, Object> facts) {
        return new AuditLine(action, actor, null, null, facts);
    }

    /** Returns a line about one person. */
    public static AuditLine about(
            final String action, final Actor actor, final DiscordId subject, final Map<String, Object> facts) {
        return new AuditLine(action, actor, Objects.requireNonNull(subject, "subject"), null, facts);
    }
}
