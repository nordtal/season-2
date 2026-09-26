package eu.nordtal.s2.common.online;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/** The only implementation of {@link OnlineRoster}; it borrows the pool it is given and owns nothing. */
final class JdbiRoster implements OnlineRoster {

    private final OnlineRosterDao dao;

    JdbiRoster(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(OnlineRosterDao.class);
    }

    @Override
    public void replace(final Collection<Presence> connected) {
        Objects.requireNonNull(connected, "connected");
        // One instant for the whole write: the prune deletes what is older, so rows must agree on now.
        final OffsetDateTime now = Instant.now().atOffset(ZoneOffset.UTC);
        final List<OnlineRosterDao.BoundPresence> rows = new ArrayList<>(connected.size());
        final Set<UUID> seen = new HashSet<>();
        for (final Presence presence : connected) {
            Objects.requireNonNull(presence, "presence");
            if (!seen.add(presence.uuid())) {
                // An UPSERT would let a duplicate win silently; a proxy reporting an account twice is a bug.
                throw new IllegalArgumentException("the same player appears twice in one write: " + presence.uuid());
            }
            rows.add(new OnlineRosterDao.BoundPresence(presence.uuid(), presence.name(), presence.subject(), now));
        }
        // An empty roster means nobody is connected, and the prune makes the table say so.
        dao.replace(rows, now);
    }

    @Override
    public List<OnlinePlayer> current() {
        return List.copyOf(dao.current());
    }
}
