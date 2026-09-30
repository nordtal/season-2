package eu.nordtal.s2.database.access;

import eu.nordtal.s2.common.id.DiscordId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Who is an admin, as a tree of grants decided in Steward.
 * An admin revokes only somebody strictly below them, with their whole branch; grants are rate limited, revocations are
 * not.
 */
public interface AdminTree {

    /** How many grants all admins together may make in one hour. */
    int GRANTS_PER_HOUR = 3;

    /** Returns a tree over a pool the caller owns. */
    static AdminTree using(final DataSource dataSource) {
        return new JdbiAdminTree(dataSource);
    }

    /** Returns whether this account is an admin right now. */
    boolean isAdmin(DiscordId discordId);

    /**
     * Makes this account the root, but only while nobody at all is an admin.
     * The first sign-in into an empty tree wins, deliberately a race.
     *
     * @return whether this account is now the root; false when anybody already was an admin
     */
    boolean claimRootIfNobody(DiscordId discordId);

    /**
     * Makes {@code target} an admin below {@code actor}.
     *
     * @return what happened; nothing is written unless it is {@link Grant#GRANTED}
     */
    Grant grant(String actor, String target);

    /**
     * Takes admin from {@code target} and from everybody below it, on behalf of {@code actor}.
     *
     * @return what happened; nothing is written unless it is {@link Revocation.Outcome#REVOKED}
     */
    Revocation revoke(String actor, String target);

    /**
     * Takes admin from this account and its whole branch, as leaving or being banned from the guild does.
     *
     * @return every account that stopped being an admin, empty when this one was not one
     */
    Set<String> dropWithBranch(DiscordId discordId);

    /** Returns every admin with their granter, root first, then in the order they were granted. */
    List<Admin> admins();

    /**
     * One admin and where they sit.
     *
     * @param grantedBy null for the root
     */
    record Admin(DiscordId discordId, String grantedBy, Instant grantedAt) {}

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
            /** The target is not strictly below the one revoking, or not an admin at all. */
            NOT_BELOW
        }

        static Revocation refused(final Outcome outcome) {
            return new Revocation(outcome, List.of());
        }
    }
}
