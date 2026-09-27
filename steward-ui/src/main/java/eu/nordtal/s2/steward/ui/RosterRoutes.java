package eu.nordtal.s2.steward.ui;

import eu.nordtal.s2.steward.ui.data.Data;
import io.javalin.http.Context;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Who is in the guild, what they paid, what they may, and the journal of it all. */
final class RosterRoutes {

    private final @Nullable Data data;

    RosterRoutes(final @Nullable Data data) {
        this.data = data;
    }

    private Data data() {
        return Objects.requireNonNull(data, "this route needs the database, which this instance has none of");
    }

    void people(final Context ctx) {
        ctx.json(data().roster().people(StewardUi.limit(ctx, 500, 2000)));
    }

    void grants(final Context ctx) {
        ctx.json(data().roster().grantsOf(ctx.pathParam("id")));
    }

    void payments(final Context ctx) {
        ctx.json(data().roster().payments(StewardUi.limit(ctx, 200, 1000)));
    }

    /**
     * What {@code access settle} may be pointed at; a list, not a limit, as {@code RosterDirectory#openPayments} says.
     */
    void openPayments(final Context ctx) {
        ctx.json(data().roster().openPayments());
    }

    void journal(final Context ctx) {
        ctx.json(data().audit()
                .search(ctx.queryParam("action"), ctx.queryParam("subject"), StewardUi.limit(ctx, 200, 1000)));
    }
}
