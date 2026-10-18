package eu.nordtal.season.database.online;

import eu.nordtal.season.database.Jdbis;
import java.time.InstantSource;
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

final class JdbiRoster implements OnlineRoster {

    private final OnlineRosterDao dao;
    private final InstantSource clock;

    JdbiRoster(final DataSource dataSource, final InstantSource clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dao = Jdbis.over(dataSource).onDemand(OnlineRosterDao.class);
    }

    @Override
    public void replace(final Collection<Presence> connected) {
        Objects.requireNonNull(connected, "connected");
        // One instant for the whole write, as the prune deletes what is older.
        final OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        final List<OnlineRosterDao.BoundPresence> rows = new ArrayList<>(connected.size());
        final Set<UUID> seen = new HashSet<>();
        for (final Presence presence : connected) {
            Objects.requireNonNull(presence, "presence");
            if (!seen.add(presence.uuid())) {
                // A duplicate is a proxy bug, which an upsert would hide.
                throw new IllegalArgumentException("the same player appears twice in one write: " + presence.uuid());
            }
            rows.add(new OnlineRosterDao.BoundPresence(presence.uuid(), presence.name(), presence.subject(), now));
        }
        // An empty roster empties the table.
        dao.replace(rows, now);
    }

    @Override
    public List<OnlinePlayer> current() {
        return List.copyOf(dao.current());
    }
}
