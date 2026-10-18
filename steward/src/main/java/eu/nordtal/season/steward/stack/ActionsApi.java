package eu.nordtal.season.steward.stack;

import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditEntry;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.audit.JournalFilter;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateRequest;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** {@code GET /api/actions} answers the newest runs of the run inbox and the newest journal entries, merged. */
public final class ActionsApi {

    /** The number of entries when the request names none. */
    public static final int DEFAULT_LIMIT = 5;

    /** The journal actions that the feed shows. */
    private static final Set<JournalAction> DISPLAYED_AUDIT_ACTIONS = Set.of(
            JournalAction.GRANT_ACCESS,
            JournalAction.REVOKE_ACCESS,
            JournalAction.LINK,
            JournalAction.UNLINK,
            JournalAction.SETTLE,
            JournalAction.SET_PHASE,
            JournalAction.REGISTER_KEY,
            JournalAction.REMOVE_KEY,
            JournalAction.ADD_AUTHENTICATOR,
            JournalAction.REMOVE_AUTHENTICATOR,
            JournalAction.FORGET_FACTORS);

    private final UpdateDirectory updates;
    private final AuditDirectory audit;

    public ActionsApi(final UpdateDirectory updates, final AuditDirectory audit) {
        this.updates = updates;
        this.audit = audit;
    }

    public void list(final Context ctx) {
        final int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(DEFAULT_LIMIT);
        ctx.json(recent(limit).stream().map(ActionEntry::wire).toList());
    }

    /**
     * Returns the merged entries, newest first.
     *
     * @param limit the maximum count, and a value below 1 counts as 1
     */
    List<ActionEntry> recent(final int limit) {
        final int clamped = Math.max(1, limit);
        final List<ActionEntry> combined = new ArrayList<>();

        for (final UpdateRequest run : updates.recent(clamped)) {
            combined.add(ActionEntry.of(run));
        }

        for (final JournalAction action : DISPLAYED_AUDIT_ACTIONS) {
            for (final AuditEntry entry : audit.search(JournalFilter.of(action), clamped)) {
                combined.add(ActionEntry.of(entry));
            }
        }

        combined.sort(Comparator.comparing(ActionEntry::occurred).reversed());
        return combined.size() > clamped ? combined.subList(0, clamped) : combined;
    }
}
