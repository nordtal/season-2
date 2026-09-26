package eu.nordtal.s2.common.access;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/**
 * The only implementation of {@link AccessRequests}. Package-private: consumers get it from the
 * factory method on the interface and never name JDBI themselves.
 *
 * <p>It borrows the pool it is given and owns nothing, which is why there is no {@code close()}
 * here and none on the interface - the process that built the pool closes the pool.</p>
 */
final class JdbiAccessRequests implements AccessRequests {

    private final AccessRequestDao dao;

    JdbiAccessRequests(final DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(AccessRequestDao.class);
    }

    @Override
    public AccessRequest submit(final NewAccessRequest request) {
        return submit(request, PATIENCE);
    }

    @Override
    public AccessRequest submit(final NewAccessRequest request, final Duration patience) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.kind(), "kind");
        Objects.requireNonNull(request.source(), "source");
        Objects.requireNonNull(request.subject(), "subject");
        // Clamped rather than rejected: a caller computing a patience from two clocks that disagree
        // should get a row that expires at once - which is visible - and not an exception on the
        // path that is trying to grant somebody the access they paid for.
        final long seconds = patience == null ? PATIENCE.toSeconds() : Math.max(0L, patience.toSeconds());
        return dao.submit(
                request.kind().name(),
                request.subject(),
                request.argument(),
                request.source().name(),
                request.requestedBy(),
                seconds);
    }

    @Override
    public Optional<AccessRequest> claim() {
        return dao.claim();
    }

    @Override
    public void finish(final long id, final boolean ok, final String result) {
        final int written =
                dao.finish(id, ok ? AccessRequestStatus.DONE.name() : AccessRequestStatus.FAILED.name(), result);
        if (written == 0) {
            // Not an exception: the work is done either way, and throwing here would turn a
            // double-settle into a failure the caller would report as one. It is worth a line,
            // because the only ways to get here are settling twice and settling a row somebody
            // else claimed.
            System.getLogger(JdbiAccessRequests.class.getName())
                    .log(System.Logger.Level.WARNING, "access request " + id + " was not RUNNING when it was settled");
        }
    }

    @Override
    public Optional<AccessRequest> outcome(final long id) {
        expireDue();
        return dao.byId(id);
    }

    @Override
    public List<AccessRequest> pending() {
        return dao.pending();
    }

    @Override
    public int expireDue() {
        return dao.expireDue();
    }

    @Override
    public int purge(final Duration age) {
        Objects.requireNonNull(age, "age");
        return dao.purge(Math.max(0L, age.toSeconds()));
    }
}
