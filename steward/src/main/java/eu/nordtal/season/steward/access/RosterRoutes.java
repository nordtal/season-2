package eu.nordtal.season.steward.access;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.audit.JournalFilter;
import eu.nordtal.season.steward.QueryLimit;
import eu.nordtal.season.steward.data.Data;
import io.javalin.http.Context;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The routes for the roster, the payments, the access and the journal. */
public final class RosterRoutes {

    private final @Nullable Data data;

    public RosterRoutes(final @Nullable Data data) {
        this.data = data;
    }

    private Data data() {
        return Objects.requireNonNull(data, "this route needs the database, which this instance has none of");
    }

    public void people(final Context ctx) {
        ctx.json(data().access().people(QueryLimit.of(ctx, 500, 2000)));
    }

    public void grants(final Context ctx) {
        ctx.json(data().access().grantsOf(DiscordId.of(ctx.pathParam("id"))));
    }

    /** Answers the payment requests of one person, newest first, up to a limit. */
    public void paymentsOf(final Context ctx) {
        ctx.json(data().payments().recentOf(DiscordId.of(ctx.pathParam("id")), QueryLimit.of(ctx, 200, 1000)));
    }

    public void payments(final Context ctx) {
        ctx.json(data().payments().recent(QueryLimit.of(ctx, 200, 1000)));
    }

    /** Answers every open payment request, oldest first, without a limit. */
    public void openPayments(final Context ctx) {
        ctx.json(data().payments().allOpen());
    }

    /** Answers the journal, filtered by {@code action}, {@code subject}, {@code reference} and {@code minDays}. */
    public void journal(final Context ctx) {
        ctx.json(data().audit()
                .search(
                        new JournalFilter(
                                ctx.queryParam("action"),
                                ctx.queryParam("subject"),
                                ctx.queryParam("reference"),
                                minDays(ctx)),
                        QueryLimit.of(ctx, 200, 1000)));
    }

    /** Returns {@code minDays}, or {@code null} when absent, and refuses a value that is not a number. */
    private static @Nullable Integer minDays(final Context ctx) {
        return ctx.queryParam("minDays") == null
                ? null
                : ctx.queryParamAsClass("minDays", Integer.class).get();
    }
}
