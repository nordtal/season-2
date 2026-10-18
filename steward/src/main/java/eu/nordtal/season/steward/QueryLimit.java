package eu.nordtal.season.steward;

import io.javalin.http.Context;

/** The {@code limit} query parameter that every list route reads. */
public final class QueryLimit {

    private QueryLimit() {}

    /** Returns the {@code limit} parameter, or {@code fallback} when absent, held between 1 and {@code ceiling}. */
    public static int of(final Context ctx, final int fallback, final int ceiling) {
        return Math.min(
                ceiling,
                Math.max(1, ctx.queryParamAsClass("limit", Integer.class).getOrDefault(fallback)));
    }
}
