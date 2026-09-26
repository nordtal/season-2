package eu.nordtal.s2.common.metric;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/**
 * The only implementation of {@link MetricDirectory}. Package-private: consumers get it from the
 * factory method on the interface and never name JDBI themselves.
 * <p>
 * It borrows the pool it is given and owns nothing, which is why there is no {@code close()} here
 * and none on the interface - the process that built the pool closes the pool.
 * </p>
 */
final class JdbiMetrics implements MetricDirectory {

    private final MetricDao dao;

    JdbiMetrics(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(MetricDao.class);
    }

    @Override
    public void record(final List<MetricSample> samples) {
        Objects.requireNonNull(samples, "samples");
        if (samples.isEmpty()) {
            // A JDBC batch of nothing is a round trip for nothing, and JDBI would refuse an empty
            // iterable outright. A sweep that measured nothing is not an error - a container that
            // has just gone away simply has no numbers this time.
            return;
        }

        final List<MetricDao.BoundSample> bound = new ArrayList<>(samples.size());
        for (final MetricSample sample : samples) {
            Objects.requireNonNull(sample, "sample");
            bound.add(new MetricDao.BoundSample(sample.subject(), sample.metric(), utc(sample.at()), sample.value()));
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
            // Empty rather than an exception: the caller is a graph being drawn, and a window that
            // has collapsed - a zoom taken to its end, a clock that moved - should leave the panel
            // blank rather than throw out of a page render.
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

    /**
     * The one conversion in this class, and the reason it exists is on {@link MetricDao}: an
     * {@code Instant} bound through JDBC is rendered in the JVM's default zone and re-read in the
     * server's, which agree until the day they do not. An {@code OffsetDateTime} at UTC carries its
     * own offset and leaves nothing to be assumed.
     */
    private static OffsetDateTime utc(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
