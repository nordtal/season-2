package eu.nordtal.s2.common.command;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import javax.sql.DataSource;

import java.util.Objects;

/**
 * The one command allowlist, as the network's five processes share it.
 *
 * <h2>One truth, written by the proxy and read by the backends</h2>
 * The list itself lives in the proxy's {@code network.yml}, because that is where an operator edits
 * it and because the proxy is the process that enforces it first - it sees every command a player
 * types, including the ones destined for a backend. The three Paper servers need the same list for
 * their own half of the job (what the client is told exists at all), and they are four containers
 * away from that file. So the proxy publishes it and they read it, on the poll and the
 * {@code LISTEN} the admin roster already taught this repository to trust.
 *
 * <p>Which means the backends can be behind by up to one poll interval after an edit, and are told
 * nothing at all if the proxy has never run. Both are deliberate: the proxy's own enforcement is
 * neither delayed nor optional, and a backend with no list falls back to what this network did
 * before the list existed - see {@code CommandFilter}, which fails <em>open</em> and says so in the
 * log rather than silently refusing every command on the server.</p>
 *
 * <h2>Nothing from JDBI appears here</h2>
 * The same rule {@code AccessDirectory} follows: the factory takes a {@code javax.sql.DataSource},
 * so a consumer never compiles against JDBI even though this is a database-backed thing.
 */
public final class AllowlistDirectory {

    private final AllowlistDao dao;

    private AllowlistDirectory(final DataSource dataSource) {
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(AllowlistDao.class);
    }

    /**
     * @param dataSource a pool this directory borrows and never closes - the caller owns it, the
     *                   way every other directory in this package works
     */
    public static AllowlistDirectory using(final DataSource dataSource) {
        return new AllowlistDirectory(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /**
     * What the network currently allows, or empty when no proxy has ever published a list.
     *
     * <h2>Why empty rather than {@link CommandAllowlist#NOTHING}</h2>
     * Because "nobody has written a list" and "the list is empty" are different situations and a
     * reader has to be able to tell them apart: the first is a proxy that has not started yet, the
     * second is an operator who emptied the list on purpose. Collapsing them would make a backend
     * that came up before the proxy refuse every command on the server, which looks exactly like
     * commands being broken.
     *
     * <p><b>Never call this on a Paper server's main thread.</b> It is a round trip, and the rule
     * this repository has had since 2026-09-01 has no exceptions.</p>
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
