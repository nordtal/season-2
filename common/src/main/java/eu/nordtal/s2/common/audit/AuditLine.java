package eu.nordtal.s2.common.audit;

import java.util.UUID;

/**
 * One journal line before it is written - the arguments of {@link AuditDirectory#record} as a value.
 *
 * It exists because one caller cannot use {@code record}: {@code steward-ui} writes a row into
 * {@code command_request} and its journal line together, in one statement, and therefore has to
 * hand the line to the thing performing the insert instead of writing it itself. Everything else in
 * this repository still calls {@code record} directly, and should - see that method for why the
 * journal is deliberately not transactional with the action it describes in the general case.
 *
 * Carries no JDBI and no SQL, so it can cross a package boundary inside {@code :common} without
 * taking a database type with it.
 *
 * @param action  a short upper-case constant, e.g. {@code COMMAND}
 * @param actor   who did it, as a person reads it; may be null for the system itself
 * @param subject what it was about; may be null
 * @param mcUuid  the Minecraft account it concerned, where there is one; may be null
 * @param detail  one line of what happened; may be null
 */
public record AuditLine(String action, String actor, String subject, UUID mcUuid, String detail) {

    public AuditLine {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("a journal line without an action is not one");
        }
    }
}
