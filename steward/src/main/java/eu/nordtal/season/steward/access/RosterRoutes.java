package eu.nordtal.season.steward.access;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.audit.JournalFilter;
import eu.nordtal.season.steward.QueryLimit;
import eu.nordtal.season.steward.data.Data;
import io.javalin.http.Context;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Who is in the guild, what they paid, what they may, and the journal of it all. */
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

    /** One person's payment requests, newest first, for their page; the list of all is a page with a limit. */
    public void paymentsOf(final Context ctx) {
        ctx.json(data().payments().recentOf(DiscordId.of(ctx.pathParam("id")), QueryLimit.of(ctx, 200, 1000)));
    }

    public void payments(final Context ctx) {
        ctx.json(data().payments().recent(QueryLimit.of(ctx, 200, 1000)));
    }

    /** What {@code access settle} may be pointed at: every open request, oldest first and without a limit. */
    public void openPayments(final Context ctx) {
        ctx.json(data().payments().allOpen());
    }

    /** The journal, narrowed by any of {@code action}, {@code subject}, {@code reference} and {@code minDays}. */
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

    /** Returns {@code minDays}, or {@code null} when absent; a value that is no number is refused as a bad request. */
    private static @Nullable Integer minDays(final Context ctx) {
        return ctx.queryParam("minDays") == null
                ? null
                : ctx.queryParamAsClass("minDays", Integer.class).get();
    }
}
