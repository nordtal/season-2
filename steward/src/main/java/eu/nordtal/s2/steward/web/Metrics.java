package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.steward.data.Data;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** {@code /api/metrics}: the curves the start page draws, read from Postgres since Docker only answers "now". */
final class Metrics {

    private final @Nullable Data data;

    private final Clock clock;

    Metrics(final @Nullable Data data, final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.data = data;
    }

    private Data data() {
        return Objects.requireNonNull(data, "this route needs the database, which this instance has none of");
    }

    void range(final Context ctx) {
        final String subject = ctx.queryParamAsClass("subject", String.class).getOrDefault("host");
        final String metric = ctx.queryParam("metric");
        if (metric == null || metric.isBlank()) {
            throw new BadRequestResponse("metric is which number to draw");
        }
        final int hours = ctx.queryParamAsClass("hours", Integer.class).getOrDefault(6);
        final Instant now = clock.instant();
        ctx.json(Map.of(
                "subject",
                subject,
                "metric",
                metric,
                "from",
                now.minus(Duration.ofHours(hours)).toString(),
                "points",
                data().metrics().range(subject, metric, now.minus(Duration.ofHours(hours)), now)));
    }
}
