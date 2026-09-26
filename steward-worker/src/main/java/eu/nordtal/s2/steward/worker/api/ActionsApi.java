package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.common.audit.AuditDirectory;
import eu.nordtal.s2.common.audit.AuditEntry;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * {@code GET /api/actions} (steward/82): the newest things that happened on this network, across
 * both tables that record one.
 *
 * <h2>Why this is a query and not two</h2>
 * Measured against the running database on 2026-09-17: an update, a restart and a backup are all
 * the same table, {@code update_request}, distinguished only by {@code kind} - so "is there a third
 * kind of row already" (the ticket's own open question) has a firm no, they are the same table's
 * {@link UpdateKind#RESTART} and {@link UpdateKind#BACKUP}. The second table is {@code audit_log},
 * the admin/access journal already served to the interface as {@code /api/journal} - but by
 * steward-ui's own backend, not this one, and only from that one table. This class is the one place
 * that reads both and answers with one sorted, truncated list, so the frontend never merges two
 * feeds and never has the chance to forget a third.
 *
 * <h2>Why the audit half is nine separate queries, not one</h2>
 * {@link AuditDirectory} does not expose "recent, filtered by a set of actions" - only
 * {@link AuditDirectory#recent(int)} (unfiltered) and {@link AuditDirectory#search(String, String,
 * int)} (exactly one action). {@code audit_log} on this deployment is dominated by {@code HELD_KEY}
 * - 14 of 35 rows measured on 2026-09-17, one for every session unlocked with a security key - so
 * asking for "the newest 5, unfiltered" and then throwing away what does not belong on this list
 * would as likely as not throw away everything. One {@code search} per {@linkplain
 * #DISPLAYED_AUDIT_ACTIONS displayed action} costs a handful of indexed reads of a small table
 * instead, and gets back exactly {@code limit} rows of each kind that exists, however deep in the
 * journal the fifth {@code GRANT_ACCESS} is hiding.
 *
 * <h2>What is deliberately left off {@link #DISPLAYED_AUDIT_ACTIONS}</h2>
 * {@code HELD_KEY} - proving who you are to look at the system is not a thing that was done to it,
 * and including it would mean this list is HELD_KEY five rows out of five on a quiet day. That is a
 * product decision this ticket's author did not sign off on explicitly; see steward/82's own
 * "Beobachtet" section for the one-line fallback if the answer is "show it anyway".
 */
public final class ActionsApi {

    /** What the interface asks for when it does not say - a screenful, same as {@code recent()}. */
    public static final int DEFAULT_LIMIT = 5;

    /**
     * The {@code audit_log.action} values worth a place on this list. Unconstrained by the schema
     * (see {@link AuditEntry#action()}), so this is necessarily a guess at what the bot writes today
     * rather than something a compiler can hold shut - {@code AuditDirectory}'s own javadoc names
     * {@code LINK, UNLINK, GRANT_ACCESS, REVOKE_ACCESS, SETTLE} as its examples, and
     * {@code RECREATE}, {@code SET_PHASE}, {@code REGISTER_KEY}, {@code REMOVE_KEY} and
     * {@code FORGET_FACTORS} are what is actually in the running database on 2026-09-17.
     */
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

    /** {@code GET /api/actions?limit=n} - a bare JSON array, newest first. */
    public void list(final Context ctx) {
        final int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(DEFAULT_LIMIT);
        // Through json(), never the records themselves - see ActionEntry#json for the Instant
        // that leaves as {"seconds":...,"nanos":...} otherwise.
        ctx.json(recent(limit).stream().map(ActionEntry::json).toList());
    }

    /**
     * @param limit how many, at most. Below 1 is clamped to 1, exactly as {@link
     *              UpdateDirectory#recent(int)} and {@link AuditDirectory#recent(int)} already
     *              clamp it
     * @return the merged, newest-first list
     */
    List<ActionEntry> recent(final int limit) {
        final int clamped = Math.max(1, limit);
        final List<ActionEntry> combined = new ArrayList<>();

        for (final UpdateRequest run : updates.recent(clamped)) {
            // APPLY is retired and refused (see UpdateKind's own javadoc) - a row of it can only be
            // history from before 2026-09-07, and this list has no use for reviving it.
            if (run.kind() == UpdateKind.APPLY) {
                continue;
            }
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
