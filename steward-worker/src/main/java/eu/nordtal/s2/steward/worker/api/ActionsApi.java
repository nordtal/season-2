package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.database.audit.AuditDirectory;
import eu.nordtal.s2.database.audit.AuditEntry;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.database.update.UpdateRequest;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * {@code GET /api/actions}: the newest runs from {@code update_request} and lines from {@code audit_log}, merged.
 *
 * The audit half is one search per displayed action, since {@code HELD_KEY} rows would crowd out the rest.
 */
public final class ActionsApi {

    /** What the interface gets when it does not ask for a number. */
    public static final int DEFAULT_LIMIT = 5;

    /** The {@code audit_log.action} values worth a place on this list; {@code HELD_KEY} is left off on purpose. */
    private static final Set<String> DISPLAYED_AUDIT_ACTIONS = Set.of(
            "GRANT_ACCESS",
            "REVOKE_ACCESS",
            "LINK",
            "UNLINK",
            "SETTLE",
            "RECREATE",
            "SET_PHASE",
            "REGISTER_KEY",
            "REMOVE_KEY",
            "FORGET_FACTORS");

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
        ctx.json(recent(limit).stream().map(ActionEntry::json).toList());
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

        for (final String action : DISPLAYED_AUDIT_ACTIONS) {
            for (final AuditEntry entry : audit.search(action, null, clamped)) {
                combined.add(ActionEntry.of(entry));
            }
        }

        combined.sort(Comparator.comparing(ActionEntry::occurred).reversed());
        return combined.size() > clamped ? combined.subList(0, clamped) : combined;
    }
}
