package eu.nordtal.season.steward.journal;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditLine;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes the journal entries of a route marked {@link Journaled} after it answered. A write failure is logged. */
public final class Journaling {

    private static final Logger log = LoggerFactory.getLogger(Journaling.class);

    /** The context attribute that holds the lines of a request. */
    private static final String LINES = "steward.journal";

    private final AuditDirectory audit;
    private final Function<Context, Optional<Actor>> actor;

    public Journaling(final AuditDirectory audit, final Function<Context, Optional<Actor>> actor) {
        this.audit = audit;
        this.actor = actor;
    }

    /** Adds an entry without a subject to this request. {@link #write} writes it after a success. */
    public static void line(final Context ctx, final JournalDetail detail) {
        line(ctx, null, detail);
    }

    /**
     * Adds an entry about {@code subject} to this request.
     *
     * @param detail the values of the entry, or {@code null} for an action without values
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

    /** Writes the lines of a {@link Journaled} route that answered below 400. A route without lines gets one. */
    public void write(final Context ctx) {
        final @Nullable Journaled journaled = ctx.routeRoles().stream()
                .filter(Journaled.class::isInstance)
                .map(Journaled.class::cast)
                .findFirst()
                .orElse(null);
        if (journaled == null || ctx.statusCode() >= 400) {
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
                audit.record(new AuditLine(journaled.action(), who.get(), line.subject(), null, line.detail()));
            } catch (final RuntimeException failed) {
                log.error("Could not write the journal entry {} {}", journaled.action(), line.detail(), failed);
            }
        }
    }

    private record Noted(
            @Nullable DiscordId subject, @Nullable JournalDetail detail) {}
}
