package eu.nordtal.s2.common.access;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The only implementation of {@link AdminTree}.
 *
 * <p><b>Every write takes one transaction-scoped advisory lock first.</b> The checks and the write
 * are separate statements - "is anybody an admin", "were three grants made this hour", "is the
 * target below me" - and two of them racing would each see the state before the other. The lock
 * makes them one at a time, which costs nothing at the rate admins are appointed.</p>
 */
final class JdbiAdminTree implements AdminTree {

    private static final String LOCK = "SELECT pg_advisory_xact_lock(hashtext('nordtal_admin_tree'))";

    /** Everybody strictly below {@code :actor}. The tree has no cycle: a grant needs a non-admin. */
    private static final String BELOW = """
            WITH RECURSIVE below (discord_id) AS (
                SELECT discord_id FROM discord_user WHERE admin AND admin_granted_by = :actor
                UNION
                SELECT u.discord_id
                FROM discord_user u
                         JOIN below b ON u.admin_granted_by = b.discord_id
                WHERE u.admin
            )
            SELECT EXISTS (SELECT 1 FROM below WHERE discord_id = :target)
            """;

    /** Clears {@code :target} and everybody below it, notifying once per account. */
    private static final String DROP_BRANCH = """
            WITH RECURSIVE branch (discord_id, depth) AS (
                SELECT discord_id, 0 FROM discord_user WHERE discord_id = :target AND admin
                UNION
                SELECT u.discord_id, b.depth + 1
                FROM discord_user u
                         JOIN branch b ON u.admin_granted_by = b.discord_id
                WHERE u.admin
            ),
                 cleared AS (
                     UPDATE discord_user
                         SET admin = false, admin_granted_by = NULL, admin_granted_at = NULL,
                             updated = now()
                         WHERE discord_id IN (SELECT discord_id FROM branch)
                         RETURNING discord_id
                 ),
                 notified AS MATERIALIZED (
                     SELECT discord_id, pg_notify('nordtal_admin', discord_id) AS sent FROM cleared
                 )
            SELECT n.discord_id
            FROM notified n
                     JOIN branch b ON b.discord_id = n.discord_id
            ORDER BY b.depth, n.discord_id
            """;

    private final Jdbi jdbi;

    JdbiAdminTree(final DataSource dataSource) {
        this.jdbi = Jdbi.create(Objects.requireNonNull(dataSource, "dataSource"))
                .installPlugin(new PostgresPlugin());
    }

    @Override
    public boolean isAdmin(final String discordId) {
        return jdbi.withHandle(handle -> admin(handle, discordId));
    }

    @Override
    public boolean claimRootIfNobody(final String discordId) {
        Objects.requireNonNull(discordId, "discordId");
        return jdbi.inTransaction(handle -> {
            handle.execute(LOCK);
            final boolean anybody = handle.createQuery("SELECT EXISTS (SELECT 1 FROM discord_user WHERE admin)")
                    .mapTo(Boolean.class).one();
            if (anybody) {
                return false;
            }
            handle.createQuery("""
                            WITH upserted AS (
                                INSERT INTO discord_user (discord_id, admin, admin_granted_at, updated)
                                VALUES (:id, true, now(), now())
                                ON CONFLICT (discord_id) DO UPDATE
                                    SET admin = true, admin_granted_by = NULL, admin_granted_at = now(),
                                        updated = now()
                                RETURNING discord_id
                            ),
                                 notified AS (
                                     SELECT pg_notify('nordtal_admin', discord_id) FROM upserted
                                 )
                            SELECT count(*) FROM notified
                            """)
                    .bind("id", discordId)
                    .mapTo(Integer.class).one();
            return true;
        });
    }

    @Override
    public Grant grant(final String actor, final String target) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(target, "target");
        return jdbi.inTransaction(handle -> {
            handle.execute(LOCK);
            if (!admin(handle, actor)) {
                return Grant.ACTOR_NOT_ADMIN;
            }
            if (admin(handle, target)) {
                return Grant.ALREADY_ADMIN;
            }
            final boolean member = handle.createQuery(
                            "SELECT EXISTS (SELECT 1 FROM discord_user WHERE discord_id = :id AND member_state = 'MEMBER')")
                    .bind("id", target)
                    .mapTo(Boolean.class).one();
            if (!member) {
                return Grant.NOT_A_MEMBER;
            }
            final int lastHour = handle.createQuery(
                            "SELECT count(*) FROM admin_grant WHERE granted > now() - interval '1 hour'")
                    .mapTo(Integer.class).one();
            if (lastHour >= GRANTS_PER_HOUR) {
                return Grant.RATE_LIMITED;
            }
            handle.createQuery("""
                            WITH granted AS (
                                UPDATE discord_user
                                    SET admin = true, admin_granted_by = :actor, admin_granted_at = now(),
                                        updated = now()
                                    WHERE discord_id = :target
                                    RETURNING discord_id
                            ),
                                 recorded AS (
                                     INSERT INTO admin_grant (discord_id, granted_by)
                                         SELECT discord_id, :actor FROM granted
                                 ),
                                 notified AS (
                                     SELECT pg_notify('nordtal_admin', discord_id) FROM granted
                                 )
                            SELECT count(*) FROM notified
                            """)
                    .bind("actor", actor)
                    .bind("target", target)
                    .mapTo(Integer.class).one();
            return Grant.GRANTED;
        });
    }

    @Override
    public Revocation revoke(final String actor, final String target) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(target, "target");
        return jdbi.inTransaction(handle -> {
            handle.execute(LOCK);
            if (!admin(handle, actor)) {
                return Revocation.refused(Revocation.Outcome.ACTOR_NOT_ADMIN);
            }
            if (actor.equals(target)) {
                return Revocation.refused(Revocation.Outcome.SELF);
            }
            final boolean below = handle.createQuery(BELOW)
                    .bind("actor", actor)
                    .bind("target", target)
                    .mapTo(Boolean.class).one();
            if (!below) {
                return Revocation.refused(Revocation.Outcome.NOT_BELOW);
            }
            return new Revocation(Revocation.Outcome.REVOKED, dropBranch(handle, target));
        });
    }

    @Override
    public Set<String> dropWithBranch(final String discordId) {
        Objects.requireNonNull(discordId, "discordId");
        return jdbi.inTransaction(handle -> {
            handle.execute(LOCK);
            return new LinkedHashSet<>(dropBranch(handle, discordId));
        });
    }

    @Override
    public List<Admin> admins() {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT discord_id, admin_granted_by, admin_granted_at
                        FROM discord_user
                        WHERE admin
                        ORDER BY admin_granted_by IS NOT NULL, admin_granted_at, discord_id
                        """)
                .map((rows, context) -> new Admin(
                        rows.getString("discord_id"),
                        rows.getString("admin_granted_by"),
                        rows.getObject("admin_granted_at", OffsetDateTime.class).toInstant()))
                .list());
    }

    private static boolean admin(final Handle handle, final String discordId) {
        return handle.createQuery("SELECT EXISTS (SELECT 1 FROM discord_user WHERE discord_id = :id AND admin)")
                .bind("id", discordId)
                .mapTo(Boolean.class).one();
    }

    private static List<String> dropBranch(final Handle handle, final String target) {
        return new ArrayList<>(handle.createQuery(DROP_BRANCH)
                .bind("target", target)
                .mapTo(String.class)
                .list());
    }
}
