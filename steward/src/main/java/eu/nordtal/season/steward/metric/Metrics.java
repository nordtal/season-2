package eu.nordtal.season.steward.metric;

import eu.nordtal.season.database.metric.MetricPoint;
import eu.nordtal.season.steward.data.Data;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** {@code /api/metrics} answers the curves of the start page from Postgres. */
public final class Metrics {

    /** The default window in minutes, six hours. */
    private static final int DEFAULT_MINUTES = 360;

    private final Data data;

    private final Clock clock;

    public Metrics(final Data data, final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.data = data;
    }

    /** The curve of one metric of one subject since {@code from}, oldest point first. */
    public record Curve(String subject, String metric, Instant from, List<MetricPoint> points) {}

    public void range(final Context ctx) {
        final String subject = ctx.queryParamAsClass("subject", String.class).getOrDefault("host");
        final String metric = ctx.queryParam("metric");
        if (metric == null || metric.isBlank()) {
            throw new BadRequestResponse("metric is which number to draw");
        }
        final int minutes = ctx.queryParamAsClass("minutes", Integer.class)
                .check(asked -> asked > 0, "minutes is how far back the curve reaches, more than none")
                .getOrDefault(DEFAULT_MINUTES);
        final Instant now = clock.instant();
        final Instant from = now.minus(Duration.ofMinutes(minutes));
        ctx.json(new Curve(subject, metric, from, data.metrics().range(subject, metric, from, now)));
    }
}
