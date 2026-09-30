package eu.nordtal.s2.database.access;

import eu.nordtal.s2.database.Jdbis;
import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/** The only implementation of {@link PackExemptions}. */
final class JdbiPackExemptions implements PackExemptions {

    private final Jdbi jdbi;

    JdbiPackExemptions(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public Outcome exempt(final String actor, final String target) {
        return change(actor, target, """
                UPDATE discord_user
                SET pack_exempt_by = :actor, pack_exempt_at = now(), updated = now()
                WHERE discord_id = :target AND pack_exempt_at IS NULL
                """);
    }

    @Override
    public Outcome enforce(final String actor, final String target) {
        return change(actor, target, """
                UPDATE discord_user
                SET pack_exempt_by = NULL, pack_exempt_at = NULL, updated = now()
                WHERE discord_id = :target AND pack_exempt_at IS NOT NULL
                """);
    }

    private Outcome change(final String actor, final String target, final String update) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(target, "target");
        return jdbi.inTransaction(handle -> {
            if (!exists(handle, "SELECT EXISTS (SELECT 1 FROM discord_user WHERE discord_id = :id AND admin)", actor)) {
                return Outcome.ACTOR_NOT_ADMIN;
            }
            if (!exists(handle, "SELECT EXISTS (SELECT 1 FROM discord_user WHERE discord_id = :id)", target)) {
                return Outcome.UNKNOWN;
            }
            final var statement = handle.createUpdate(update).bind("target", target);
            if (update.contains(":actor")) {
                statement.bind("actor", actor);
            }
            final int rows = statement.execute();
            return rows == 0 ? Outcome.UNCHANGED : Outcome.CHANGED;
        });
    }

    private static boolean exists(final Handle handle, final String query, final String id) {
        return handle.createQuery(query).bind("id", id).mapTo(Boolean.class).one();
    }
}
