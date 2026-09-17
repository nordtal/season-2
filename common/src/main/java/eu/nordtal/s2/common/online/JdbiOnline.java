package eu.nordtal.s2.common.online;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import javax.sql.DataSource;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The only implementation of {@link OnlineDirectory}. Package-private: consumers get it from the
 * factory method on the interface and never name JDBI themselves.
 * <p>
 * It borrows the pool it is given and owns nothing, which is why there is no {@code close()} here
 * and none on the interface - the process that built the pool closes the pool.
 * </p>
 */
final class JdbiOnline implements OnlineDirectory {

    private final OnlineDao dao;

    JdbiOnline(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(OnlineDao.class);
    }

    @Override
    public void write(final Map<String, Integer> counts) {
        Objects.requireNonNull(counts, "counts");
        if (counts.isEmpty()) {
            // A JDBC batch of nothing is a round trip for nothing, and JDBI refuses an empty
            // iterable outright - the same guard JdbiMetrics keeps for the same statement shape.
            return;
        }

        final List<OnlineDao.BoundCount> rows = new ArrayList<>(counts.size());
        final var now = java.time.Instant.now().atOffset(ZoneOffset.UTC);
        for (final Map.Entry<String, Integer> entry : counts.entrySet()) {
            final String subject = Objects.requireNonNull(entry.getKey(), "subject");
            final Integer players = Objects.requireNonNull(entry.getValue(), "players");
            if (players < 0) {
                throw new IllegalArgumentException(
                        "players must not be negative, was " + players + " for " + subject);
            }
            rows.add(new OnlineDao.BoundCount(subject, players, now));
        }
        dao.write(rows);
    }

    @Override
    public Map<String, OnlineCount> current() {
        final Map<String, OnlineCount> bySubject = new LinkedHashMap<>();
        for (final OnlineCount count : dao.current()) {
            bySubject.put(count.subject(), count);
        }
        return bySubject;
    }
}
