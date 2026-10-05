package eu.nordtal.season.database.metric;

import eu.nordtal.season.database.Jdbis;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** The only implementation of {@link MetricDirectory}; it borrows its pool and owns nothing. */
final class JdbiMetrics implements MetricDirectory {

    private final MetricDao dao;

    JdbiMetrics(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbis.over(dataSource).onDemand(MetricDao.class);
    }

    @Override
    public void record(final List<MetricSample> samples) {
        Objects.requireNonNull(samples, "samples");
        if (samples.isEmpty()) {
            // JDBI refuses an empty batch, and a sweep that measured nothing is not an error.
            return;
        }

        final List<MetricDao.BoundSample> bound = new ArrayList<>(samples.size());
        for (final MetricSample sample : samples) {
            Objects.requireNonNull(sample, "sample");
            bound.add(new MetricDao.BoundSample(
                    sample.subject(), sample.metric().key(), utc(sample.at()), sample.value()));
        }
        dao.record(bound);
    }

    @Override
    public List<MetricPoint> range(final String subject, final String metric, final Instant from, final Instant to) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!to.isAfter(from)) {
            // A collapsed window leaves a graph blank rather than failing a page render.
            return List.of();
        }
        return dao.range(subject, metric, utc(from), utc(to));
    }

    @Override
    public int compact(final Instant olderThan) {
        Objects.requireNonNull(olderThan, "olderThan");
        return dao.compactInto(utc(Resolution.hourOf(olderThan)));
    }

    @Override
    public int forget(final Instant olderThan) {
        Objects.requireNonNull(olderThan, "olderThan");
        return dao.forget(utc(Resolution.hourOf(olderThan)));
    }

    /** Converts to an {@code OffsetDateTime} at UTC, so no JVM or server zone is assumed. */
    private static OffsetDateTime utc(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
