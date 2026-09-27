package eu.nordtal.s2.common.command;

import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/**
 * The one command allowlist, published by the proxy and read by the backends.
 * A backend can lag one poll interval, and with no list published it fails open (see {@code CommandFilter}).
 */
public final class AllowlistDirectory {

    private final AllowlistDao dao;

    private AllowlistDirectory(final DataSource dataSource) {
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(AllowlistDao.class);
    }

    /** Returns a directory over a pool it borrows and never closes. */
    public static AllowlistDirectory using(final DataSource dataSource) {
        return new AllowlistDirectory(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /**
     * Returns what the network allows, or empty when no proxy has ever published a list.
     * Empty differs from {@link CommandAllowlist#NOTHING}; it is a round trip, never for a main thread.
     */
    public java.util.Optional<CommandAllowlist> published() {
        return dao.read(AllowlistDao.KEY).map(CommandAllowlist::deserialise);
    }

    /**
     * Publishes the list, and wakes the backends only if it actually moved.
     *
     * @return whether this call changed the stored value
     */
    public boolean publish(final CommandAllowlist allowlist) {
        Objects.requireNonNull(allowlist, "allowlist");
        if (dao.write(AllowlistDao.KEY, allowlist.serialise()) == 0) {
            return false;
        }
        dao.notifyChanged();
        return true;
    }
}
