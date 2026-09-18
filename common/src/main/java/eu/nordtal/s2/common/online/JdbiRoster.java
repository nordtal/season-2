package eu.nordtal.s2.common.online;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import javax.sql.DataSource;
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

/**
 * The only implementation of {@link OnlineRoster}. Package-private for {@link JdbiOnline}'s reason:
 * consumers get it from the factory method on the interface and never name JDBI themselves.
 * <p>
 * It borrows the pool it is given and owns nothing.
 * </p>
 */
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
        // One instant for the whole write, and not Instant.now() per row: the prune below deletes
        // exactly what is older than this value, so two rows of one write disagreeing about "now"
        // would delete one of them again on the spot.
        final OffsetDateTime now = Instant.now().atOffset(ZoneOffset.UTC);
        final List<OnlineRosterDao.BoundPresence> rows = new ArrayList<>(connected.size());
        final Set<UUID> seen = new HashSet<>();
        for (final Presence presence : connected) {
            Objects.requireNonNull(presence, "presence");
            if (!seen.add(presence.uuid())) {
                // Loudly, and before anything is written: with an UPSERT a duplicate would simply
                // let the last one win, silently, and a proxy that reported the same account twice
                // is a bug worth seeing rather than a row worth picking.
                throw new IllegalArgumentException(
                        "the same player appears twice in one write: " + presence.uuid());
            }
            rows.add(new OnlineRosterDao.BoundPresence(
                    presence.uuid(), presence.name(), presence.subject(), now));
        }
        // No early return for an empty collection - unlike JdbiOnline#write, an empty roster is a
        // statement ("nobody is connected") and the prune is what makes the table say it.
        dao.replace(rows, now);
    }

    @Override
    public List<OnlinePlayer> current() {
        return List.copyOf(dao.current());
    }
}
