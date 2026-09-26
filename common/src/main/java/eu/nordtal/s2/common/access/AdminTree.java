package eu.nordtal.s2.common.access;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Who is an admin, as a tree of grants decided in Steward.
 *
 * <p><b>The database is the source and the Discord admin role is its mirror.</b> Every admin but
 * one was granted by another admin; the one without a granter is the root, whoever completed
 * Steward's sign-in first while nobody was an admin. An admin revokes only somebody strictly below
 * them, and a revocation takes the revoked admin's whole branch with it. Nobody revokes themselves,
 * the root included.</p>
 *
 * <p><b>Grants are limited, revocations are not.</b> {@value #GRANTS_PER_HOUR} grants an hour
 * across all admins together; the next one is refused at once rather than queued. A revocation is
 * the answer to a grant that should not have happened, and limiting it would protect the mistake.</p>
 *
 * <p>Every change notifies {@code nordtal_admin} inside the statement that makes it, so it reaches
 * connected sessions only once it committed. {@code discord_user.admin} stays the flag every other
 * reader uses; this interface is the only thing that writes it.</p>
 */
public interface AdminTree {

    /** How many grants all admins together may make in one hour. */
    int GRANTS_PER_HOUR = 3;

    /**
     * @param dataSource a pool the caller owns
     * @return a tree over that pool
     */
    static AdminTree using(final DataSource dataSource) {
        return new JdbiAdminTree(dataSource);
    }

    /** @return whether this account is an admin right now */
    boolean isAdmin(String discordId);

    /**
     * Makes this account the root, but only while nobody at all is an admin.
     *
     * <p>This is the bootstrap and there is no other: the first sign-in into an empty tree wins,
     * deliberately a race. Creates the {@code discord_user} row if the bot has not written it yet.</p>
     *
     * @return whether this account is now the root; false when anybody already was an admin
     */
    boolean claimRootIfNobody(String discordId);

    /**
     * {@code actor} makes {@code target} an admin below themselves.
     *
     * @return what happened; nothing is written unless it is {@link Grant#GRANTED}
     */
    Grant grant(String actor, String target);

    /**
     * {@code actor} takes admin from {@code target} and from everybody below {@code target}.
     *
     * @return what happened; nothing is written unless it is {@link Revocation.Outcome#REVOKED}
     */
    Revocation revoke(String actor, String target);

    /**
     * Takes admin from this account and its whole branch, whoever is above it - what leaving or
     * being banned from the guild does, the one way out of the tree that is not a revocation.
     *
     * @return every account that stopped being an admin, empty when this one was not one
     */
    Set<String> dropWithBranch(String discordId);

    /** @return every admin with their granter, root first, then in the order they were granted */
    List<Admin> admins();

    /**
     * One admin and where they sit.
     *
     * @param grantedBy null for the root
     */
    record Admin(String discordId, String grantedBy, Instant grantedAt) {}

    /** The answer to {@link #grant(String, String)}. */
    enum Grant {
        GRANTED,
        /** The one granting is not an admin (any more). */
        ACTOR_NOT_ADMIN,
        ALREADY_ADMIN,
        /** The target is not a member of the guild as the bot last saw it, or unknown to it. */
        NOT_A_MEMBER,
        /** {@value AdminTree#GRANTS_PER_HOUR} grants were made in the last hour. */
        RATE_LIMITED
    }

    /**
     * The answer to {@link #revoke(String, String)}.
     *
     * @param removed every account that stopped being an admin, the target first
     */
    record Revocation(Outcome outcome, List<String> removed) {

        public enum Outcome {
            REVOKED,
            ACTOR_NOT_ADMIN,
            /** Nobody revokes themselves, not even the root. */
            SELF,
            /** The target is not strictly below the one revoking - or not an admin at all. */
            NOT_BELOW
        }

        static Revocation refused(final Outcome outcome) {
            return new Revocation(outcome, List.of());
        }
    }
}
