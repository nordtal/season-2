package eu.nordtal.season.steward.journal;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalDetail;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place a request's journal entry is written: after a route marked {@link Journaled} answered with success.
 *
 * Failed bookkeeping is logged and never fails the request, since the change itself has already happened.
 */
public final class Journaling {

    private static final Logger log = LoggerFactory.getLogger(Journaling.class);

    /** Where a handler parks the values of its lines until the route has answered. */
    private static final String LINES = "steward.journal";

    private final @Nullable AuditDirectory audit;
    private final Function<Context, Optional<Actor>> actor;

    /** @param audit the journal, null only in a test without a database */
    public Journaling(final @Nullable AuditDirectory audit, final Function<Context, Optional<Actor>> actor) {
        this.audit = audit;
        this.actor = actor;
    }

    /** Names an entry of this request about nobody in particular; it is written only if the request succeeds. */
    public static void line(final Context ctx, final JournalDetail detail) {
        line(ctx, null, detail);
    }

    /**
     * Names an entry of this request about {@code subject}; a request may name several.
     *
     * @param detail its values, {@code null} for an action that has none
     */
    public static void line(
            final Context ctx, final @Nullable DiscordId subject, final @Nullable JournalDetail detail) {
        List<Noted> noted = ctx.attribute(LINES);
        if (noted == null) {
            noted = new ArrayList<>();
            ctx.attribute(LINES, noted);
        }
        noted.add(new Noted(subject, detail));
    }

    /**
     * Writes the lines of a {@link Journaled} route that answered below 400, after its handler.
     *
     * A route that named none still gets one entry, its action alone, so nothing it did goes unrecorded.
     */
    public void write(final Context ctx) {
        final @Nullable Journaled journaled = ctx.routeRoles().stream()
                .filter(Journaled.class::isInstance)
                .map(Journaled.class::cast)
                .findFirst()
                .orElse(null);
        final AuditDirectory directory = audit;
        if (journaled == null || directory == null || ctx.statusCode() >= 400) {
            return;
        }
        final Optional<Actor> who = actor.apply(ctx);
        if (who.isEmpty()) {
            log.error("{} {} succeeded without a signed-in admin, so it has no journal line", ctx.method(), ctx.path());
            return;
        }
        final List<Noted> noted = ctx.attribute(LINES);
        final List<Noted> lines = noted == null || noted.isEmpty() ? List.of(new Noted(null, null)) : noted;
        if (noted == null || noted.isEmpty()) {
            log.warn("{} {} named no journal line, so it is journaled by its path", ctx.method(), ctx.path());
        }
        for (final Noted line : lines) {
            try {
                directory.record(new AuditLine(journaled.action(), who.get(), line.subject(), null, line.detail()));
            } catch (final RuntimeException failed) {
                log.error("Could not write the journal entry {} {}", journaled.action(), line.detail(), failed);
            }
        }
    }

    /** One line a handler named, before it is written. */
    private record Noted(
            @Nullable DiscordId subject, @Nullable JournalDetail detail) {}
}
