package eu.nordtal.season.database.metric;

import eu.nordtal.season.database.Jdbis;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToIntFunction;
import javax.sql.DataSource;

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
            // JDBI refuses an empty batch.
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
            // A collapsed window returns no points.
            return List.of();
        }
        return dao.range(subject, metric, utc(from), utc(to));
    }

    @Override
    public int compact(final Instant olderThan) {
        Objects.requireNonNull(olderThan, "olderThan");
        return walk(olderThan, dao::compactHour);
    }

    @Override
    public int forget(final Instant olderThan) {
        Objects.requireNonNull(olderThan, "olderThan");
        return walk(olderThan, dao::forgetHour);
    }

    /**
     * Applies {@code statement} to each UTC hour with raw samples before the hour of {@code olderThan}, oldest first.
     * Each hour commits on its own, so a failure leaves the earlier hours done.
     */
    private int walk(final Instant olderThan, final ToIntFunction<OffsetDateTime> statement) {
        final OffsetDateTime cut = utc(Resolution.hourOf(olderThan));
        int total = 0;
        OffsetDateTime from = utc(Instant.EPOCH);
        for (Optional<OffsetDateTime> oldest = dao.oldestRaw(from, cut);
                oldest.isPresent();
                oldest = dao.oldestRaw(from, cut)) {
            final OffsetDateTime hour = utc(Resolution.hourOf(oldest.get().toInstant()));
            total += statement.applyAsInt(hour);
            from = hour.plusHours(1);
        }
        return total;
    }

    private static OffsetDateTime utc(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
