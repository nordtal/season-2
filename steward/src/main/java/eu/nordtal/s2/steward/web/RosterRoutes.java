package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.steward.data.Data;
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
        ctx.json(data().access().people(Web.limit(ctx, 500, 2000)));
    }

    void grants(final Context ctx) {
        ctx.json(data().access().grantsOf(DiscordId.of(ctx.pathParam("id"))));
    }

    void payments(final Context ctx) {
        ctx.json(data().payments().recent(Web.limit(ctx, 200, 1000)));
    }

    /** What {@code access settle} may be pointed at: every open request, oldest first and without a limit. */
    void openPayments(final Context ctx) {
        ctx.json(data().payments().allOpen());
    }

    void journal(final Context ctx) {
        ctx.json(data().audit().search(ctx.queryParam("action"), ctx.queryParam("subject"), Web.limit(ctx, 200, 1000)));
    }
}
