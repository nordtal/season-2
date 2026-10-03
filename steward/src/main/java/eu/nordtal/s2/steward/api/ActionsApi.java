package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.database.audit.AuditDirectory;
import eu.nordtal.s2.database.audit.AuditEntry;
import eu.nordtal.s2.database.audit.JournalAction;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.database.update.UpdateRequest;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * {@code GET /api/actions}: the newest runs from the run inbox and lines from {@code audit_log}, merged.
 *
 * The audit half is one search per displayed action, since {@code HELD_KEY} rows would crowd out the rest.
 */
public final class ActionsApi {

    /** What the interface gets when it does not ask for a number. */
    public static final int DEFAULT_LIMIT = 5;

    /** The {@code audit_log.action} values worth a place on this list; {@code HELD_KEY} is left off on purpose. */
    private static final Set<JournalAction> DISPLAYED_AUDIT_ACTIONS = Set.of(
            JournalAction.GRANT_ACCESS,
            JournalAction.REVOKE_ACCESS,
            JournalAction.LINK,
            JournalAction.UNLINK,
            JournalAction.SETTLE,
            JournalAction.SET_PHASE,
            JournalAction.REGISTER_KEY,
            JournalAction.REMOVE_KEY,
            JournalAction.FORGET_FACTORS);

    private final UpdateDirectory updates;
    private final AuditDirectory audit;

    public ActionsApi(final UpdateDirectory updates, final AuditDirectory audit) {
        this.updates = updates;
        this.audit = audit;
    }

    /** {@code GET /api/actions?limit=n}: a bare JSON array, newest first. */
    public void list(final Context ctx) {
        final int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(DEFAULT_LIMIT);
        // Through json(), never the records themselves.
        ctx.json(recent(limit).stream().map(ActionEntry::wire).toList());
    }

    /**
     * The merged, newest-first list.
     *
     * @param limit how many, at most; below 1 is clamped to 1
     */
    List<ActionEntry> recent(final int limit) {
        final int clamped = Math.max(1, limit);
        final List<ActionEntry> combined = new ArrayList<>();

        for (final UpdateRequest run : updates.recent(clamped)) {
            combined.add(ActionEntry.of(run));
        }

        for (final JournalAction action : DISPLAYED_AUDIT_ACTIONS) {
            for (final AuditEntry entry : audit.search(action.name(), null, clamped)) {
                combined.add(ActionEntry.of(entry));
            }
        }

        combined.sort(Comparator.comparing(ActionEntry::occurred).reversed());
        return combined.size() > clamped ? combined.subList(0, clamped) : combined;
    }
}
