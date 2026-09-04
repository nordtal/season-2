package eu.nordtal.s2.common.command;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import javax.sql.DataSource;

import java.util.Objects;
import java.util.Optional;

/** {@link CommandRequests} over JDBI. Package-private: {@code CommandRequests} is the API. */
final class JdbiCommandRequests implements CommandRequests {

    private final CommandRequestDao dao;

    private JdbiCommandRequests(final DataSource dataSource) {
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(CommandRequestDao.class);
    }

    static CommandRequests borrowing(final DataSource dataSource) {
        return new JdbiCommandRequests(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public long submit(final NewCommandRequest request) {
        Objects.requireNonNull(request, "request");
        return dao.submit(request.target(), request.command(), request.arguments(),
                request.source(), request.requestedBy(),
                request.discordId().orElse(null), request.minecraftId().orElse(null),
                request.locale(), request.expires());
    }

    @Override
    public Optional<CommandRequest> claim(final String target) {
        return dao.claim(Objects.requireNonNull(target, "target"));
    }

    @Override
    public void finish(final long id, final boolean ok, final String result) {
        // No exception when nothing was updated. A row that is no longer RUNNING was expired by its
        // asker or settled twice by a confused target, and neither is worth throwing over inside
        // whatever thread just finished running somebody's command - the answer is simply nowhere
        // to put. The row count is returned by the DAO so a caller that cares can look.
        dao.finish(id, ok ? "DONE" : "FAILED", result);
    }

    @Override
    public boolean expire(final long id) {
        return dao.expire(id) > 0;
    }

    @Override
    public Optional<CommandOutcome> outcome(final long id) {
        return dao.outcome(id).map(row -> new CommandOutcome(
                CommandOutcome.Status.valueOf(row.status()),
                Optional.ofNullable(row.result())));
    }

    @Override
    public void close() {
        // Nothing to close: the pool belongs to whoever handed it over. The method is on the
        // interface anyway so that a future implementation which owns one can be dropped in without
        // every caller learning about it.
    }
}
