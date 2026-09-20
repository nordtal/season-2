package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.commands.access.AccessEffects;
import eu.nordtal.s2.common.access.AccessRequest;
import eu.nordtal.s2.common.access.AccessRequests;

import org.slf4j.Logger;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Carries out whatever {@code access_request} holds (season-2-community/08).
 *
 * <h2>Why this is the only place an access change happens</h2>
 * A grant is four things - a row, a Discord role, a direct message in the recipient's own language
 * and a line in the admin channel - and only this process holds a JDA session. Every other surface
 * that tried to do it itself did the first one and silently skipped the rest. So every surface
 * writes a row and this reads it.
 *
 * <h2>The loop is the house rule, not a variation on it</h2>
 * {@code CLAUDE.md}'s LISTEN/NOTIFY paragraph, unchanged: the poll is the guarantee, the
 * notification only makes it immediate, and every wake-up <b>and every reconnect</b> drains the
 * queue in full rather than trusting the signal to have arrived. {@link #drain()} is therefore safe
 * to call at any time and from any of those three reasons.
 *
 * <h2>What a failure does</h2>
 * A request that throws is written back {@code FAILED} with the message, and the loop carries on to
 * the next row. It is not retried: the effects are not idempotent - a retried grant is a second
 * grant - and the row carrying its own failure is what lets a surface say so rather than wait.
 */
public final class AccessInbox {

    private final AccessRequests inbox;
    private final AccessChanges effects;
    private final Logger log;

    public AccessInbox(final AccessRequests inbox, final AccessChanges effects,
                       final Logger log) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Claims and carries out every request waiting, oldest first.
     *
     * <p>Loops until the claim comes back empty rather than taking one row per wake-up: one
     * notification can stand for several rows, and a notification can be missed altogether.</p>
     *
     * @return how many requests were carried out or failed in this pass
     */
    public int drain() {
        // The sweep first, and it is not tidiness. A row past its patience is already dead - the
        // claim below refuses it - and a dead row still labelled PENDING is a row that looks like
        // work nobody has got to yet. The other half of the same sweep lives in
        // `AccessRequests#outcome`, for the case this one cannot cover: a bot that is not running
        // at all, which is precisely the case the patience exists for.
        final int given = inbox.expireDue();
        if (given > 0) {
            log.warn("{} access request(s) were never picked up in time and have been given up on",
                    given);
        }
        int done = 0;
        for (Optional<AccessRequest> claimed = inbox.claim();
             claimed.isPresent();
             claimed = inbox.claim()) {
            carryOut(claimed.get());
            done++;
        }
        return done;
    }

    private void carryOut(final AccessRequest request) {
        final Actor by = Actor.asked(request.requestedBy());
        try {
            inbox.finish(request.id(), true, run(request, by));
            log.info("access request {} ({} for {}) carried out", request.id(), request.kind(),
                    request.subject());
        } catch (final RuntimeException failure) {
            // The message and not the stack trace: the row is read back by a web interface, and a
            // stack trace in a toast helps nobody. The trace goes to the log, where it belongs.
            log.error("access request {} ({} for {}) failed", request.id(), request.kind(),
                    request.subject(), failure);
            inbox.finish(request.id(), false, json("error", String.valueOf(failure.getMessage())));
        }
    }

    /** @return the answer, as JSON, in the row's own shape. */
    private String run(final AccessRequest request, final Actor by) {
        return switch (request.kind()) {
            case GRANT -> {
                final Instant until =
                        effects.grant(request.subject(), Math.toIntExact(request.number()), by);
                yield json("until", until.toString());
            }
            case REVOKE -> json("revoked", String.valueOf(effects.revoke(request.subject(), by)));
            case UNLINK -> json("unlinked", String.valueOf(effects.unlink(request.subject(), by)));
            case SETTLE -> {
                final AccessEffects.Settled settled = effects.settle(request.subject(), by);
                // `until` is null for both refusals, and the row says so rather than inventing an
                // instant - a surface reading this has to be able to tell "booked until then" from
                // "there was nothing to book".
                yield json("outcome", settled.outcome().name(),
                        "days", String.valueOf(settled.days()),
                        "until", settled.until() == null ? null : settled.until().toString(),
                        "was", settled.status());
            }
            case SET_PLAYTIME -> {
                effects.setPlaytime(request.subject(), request.number(), by);
                yield json("seconds", String.valueOf(request.number()));
            }
            case RELOAD_MESSAGES -> {
                if (!effects.reloadMessages()) {
                    // A bundle that no longer parses leaves the running one in place, so this is a
                    // failure and has to read as one: the surface that asked must say "not applied"
                    // rather than "applied, nothing to report".
                    throw new IllegalStateException("the message bundles could not be re-read;"
                            + " the running ones are unchanged");
                }
                // The typos, by name. A key in the override that no bundle declares does nothing at
                // all today, silently, and naming it is the whole reason /access reload prints
                // anything.
                yield json("unknown", String.join(",", effects.unknownOverrideKeys()));
            }
        };
    }

    /**
     * The smallest JSON writer that is still correct.
     *
     * <p>A dependency would be Jackson, which this project took out of {@code jcore} on purpose, and
     * the whole of what is written here is a flat map of strings. What it does do properly is
     * escape, because an exception message contains a quotation mark sooner or later and a result
     * column that is not parseable is a result nobody can read.</p>
     *
     * @param pairs key, value, key, value. A {@code null} value is written as JSON null
     */
    static String json(final String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("json() takes pairs");
        }
        final StringBuilder out = new StringBuilder("{");
        for (int at = 0; at < pairs.length; at += 2) {
            if (at > 0) {
                out.append(',');
            }
            out.append('"').append(escape(pairs[at])).append("\":");
            if (pairs[at + 1] == null) {
                out.append("null");
            } else {
                out.append('"').append(escape(pairs[at + 1])).append('"');
            }
        }
        return out.append('}').toString();
    }

    private static String escape(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        for (int at = 0; at < text.length(); at++) {
            final char one = text.charAt(at);
            switch (one) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (one < 0x20) {
                        out.append(String.format("\\u%04x", (int) one));
                    } else {
                        out.append(one);
                    }
                }
            }
        }
        return out.toString();
    }
}
