package eu.nordtal.season.steward.access;

import eu.nordtal.season.common.id.DiscordId;
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

    public void payments(final Context ctx) {
        ctx.json(data().payments().recent(QueryLimit.of(ctx, 200, 1000)));
    }

    /** What {@code access settle} may be pointed at: every open request, oldest first and without a limit. */
    public void openPayments(final Context ctx) {
        ctx.json(data().payments().allOpen());
    }

    public void journal(final Context ctx) {
        ctx.json(data().audit()
                .search(ctx.queryParam("action"), ctx.queryParam("subject"), QueryLimit.of(ctx, 200, 1000)));
    }
}
