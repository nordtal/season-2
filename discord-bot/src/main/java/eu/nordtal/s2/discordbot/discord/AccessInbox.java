package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.commands.access.AccessEffects;
import eu.nordtal.s2.common.access.AccessRequest;
import eu.nordtal.s2.common.access.AccessRequests;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Carries out whatever {@code access_request} holds.
 *
 * A grant is four things - a row, a Discord role, a direct message in the recipient's own language and a line in the
 * admin channel - and only this process holds a JDA session. So every surface writes a row and this reads it, rather
 * than carrying out any of the four itself.
 *
 * The poll is the guarantee, the notification only makes it immediate, and every wake-up and every reconnect drains
 * the queue in full rather than trusting the signal to have arrived. {@link #drain()} is therefore safe to call at
 * any time and from any of those reasons.
 *
 * A request that throws is written back {@code FAILED} with the message, and the loop carries on to the next row. It
 * is not retried: the effects are not idempotent - a retried grant is a second grant - and the row carrying its own
 * failure is what lets a surface say so rather than wait.
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
     * Claims and carries out every request waiting, oldest first.
     *
     * Loops until the claim comes back empty rather than taking one row per wake-up: one notification can stand for
     * several rows, and a notification can be missed altogether.
     *
     * @return how many requests were carried out or failed in this pass
     */
    public int drain() {
        // Not tidiness: a row past its patience is dead, and a dead PENDING row looks like open work otherwise.
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
            // The message, not the stack trace: a web interface reads the row, and a trace in a toast helps nobody.
            log.error("access request {} ({} for {}) failed", request.id(), request.kind(), request.subject(), failure);
            inbox.finish(request.id(), false, json("error", String.valueOf(failure.getMessage())));
        }
    }

    /** @return the answer, as JSON, in the row's own shape. */
    private String run(final AccessRequest request, final Actor by) {
        return switch (request.kind()) {
            case GRANT -> {
                final Instant until = effects.grant(request.subject(), Math.toIntExact(request.number()), by);
                yield json("until", until.toString());
            }
            case REVOKE -> json("revoked", String.valueOf(effects.revoke(request.subject(), by)));
            case UNLINK -> json("unlinked", String.valueOf(effects.unlink(request.subject(), by)));
            case SETTLE -> {
                final AccessEffects.Settled settled = effects.settle(request.subject(), by);
                // `until` is null for both refusals; a surface reading this must tell "booked" from "nothing to book".
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
                // The typos, by name: an override key no bundle declares does nothing silently, so this prints them.
                yield json("unknown", String.join(",", effects.unknownOverrideKeys()));
            }
        };
    }

    /**
     * The smallest JSON writer that is still correct.
     *
     * A dependency would be Jackson, which this project took out of {@code jcore} on purpose, and the whole of what
     * is written here is a flat map of strings. What it does do properly is escape, because an exception message
     * contains a quotation mark sooner or later, and a result column that does not parse is one nobody can read.
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
