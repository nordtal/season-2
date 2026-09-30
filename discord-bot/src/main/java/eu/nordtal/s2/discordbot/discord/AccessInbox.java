package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.database.access.AccessRequest;
import eu.nordtal.s2.database.access.AccessRequests;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Carries out whatever {@code access_request} holds, since only this process holds a JDA session.
 *
 * A request that throws is written back {@code FAILED} and never retried, since a retried grant is a second grant.
 */
public final class AccessInbox {

    private final AccessRequests inbox;
    private final AccessChanges effects;
    private final Logger log;

    public AccessInbox(final AccessRequests inbox, final AccessChanges effects, final Logger log) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Claims and carries out every waiting request, oldest first, until the claim comes back empty.
     *
     * @return how many requests were carried out or failed in this pass
     */
    public int drain() {
        // A row past its patience is dead, and would otherwise look like open work.
        final int given = inbox.expireDue();
        if (given > 0) {
            log.warn("{} access request(s) were never picked up in time and have been given up on", given);
        }
        int done = 0;
        for (Optional<AccessRequest> claimed = inbox.claim(); claimed.isPresent(); claimed = inbox.claim()) {
            carryOut(claimed.get());
            done++;
        }
        return done;
    }

    private void carryOut(final AccessRequest request) {
        final Actor by = Actor.asked(request.requestedBy());
        try {
            inbox.finish(request.id(), true, run(request, by));
            log.info("access request {} ({} for {}) carried out", request.id(), request.kind(), request.subject());
        } catch (final RuntimeException failure) {
            // The message, not the stack trace: a web interface reads the row.
            log.error("access request {} ({} for {}) failed", request.id(), request.kind(), request.subject(), failure);
            inbox.finish(request.id(), false, json("error", String.valueOf(failure.getMessage())));
        }
    }

    /** Returns the answer as JSON, in the row's own shape. */
    private String run(final AccessRequest request, final Actor by) {
        return switch (request.kind()) {
            case GRANT -> {
                final Instant until = effects.grant(request.subject(), Math.toIntExact(request.number()), by);
                yield json("until", until.toString());
            }
            case REVOKE -> json("revoked", String.valueOf(effects.revoke(request.subject(), by)));
            case UNLINK -> json("unlinked", String.valueOf(effects.unlink(request.subject(), by)));
            case SETTLE -> {
                final AccessChanges.Settled settled = effects.settle(request.subject(), by);
                // `until` is null for both refusals; a surface must tell "booked" from "nothing to book".
                yield json(
                        "outcome",
                        settled.outcome().name(),
                        "days",
                        String.valueOf(settled.days()),
                        "until",
                        settled.until() == null ? null : settled.until().toString(),
                        "was",
                        settled.status());
            }
            case SET_PLAYTIME -> {
                effects.setPlaytime(request.subject(), request.number(), by);
                yield json("seconds", String.valueOf(request.number()));
            }
            case RELOAD_MESSAGES -> {
                if (!effects.reloadMessages()) {
                    // A bundle that no longer parses keeps the running one; the surface must say "not applied".
                    throw new IllegalStateException(
                            "the message bundles could not be re-read;" + " the running ones are unchanged");
                }
                // Names the override keys no bundle declares, which would otherwise do nothing silently.
                yield json("unknown", String.join(",", effects.unknownOverrideKeys()));
            }
        };
    }

    /**
     * Writes a flat map of strings as JSON, escaping properly.
     *
     * @param pairs key, value, key, value. A {@code null} value is written as JSON null
     */
    static String json(final @Nullable String... pairs) {
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
