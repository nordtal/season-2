package eu.nordtal.season.database.access;

import eu.nordtal.season.common.id.DiscordId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Who is an admin, as a tree of grants decided in Steward.
 * An admin revokes only somebody strictly below them, with their whole branch.
 * Grants are rate limited, revocations are not.
 */
public interface AdminTree {

    /** The grants that all admins together may make in one hour. */
    int GRANTS_PER_HOUR = 3;

    /** Returns a tree that borrows {@code dataSource}. */
    static AdminTree using(final DataSource dataSource) {
        return new JdbiAdminTree(dataSource);
    }

    boolean isAdmin(DiscordId discordId);

    /**
     * Makes this account the root, only while nobody is an admin.
     * Steward asks it only for the account that {@code discord.root-id} names.
     *
     * @return whether this account is the root now
     */
    boolean claimRootIfNobody(DiscordId discordId);

    /**
     * Makes {@code target} an admin below {@code actor}.
     *
     * @return what happened. Nothing is written unless it is {@link Grant#GRANTED}
     */
    Grant grant(String actor, String target);

    /**
     * Removes admin from {@code target} and everybody below it, for {@code actor}.
     *
     * @return what happened. Nothing is written unless it is {@link Revocation.Outcome#REVOKED}
     */
    Revocation revoke(String actor, String target);

    /**
     * Removes admin from this account and its branch, as leaving or being banned from the guild does.
     *
     * @return the accounts that stopped being admins, empty if this one was none
     */
    Set<String> dropWithBranch(DiscordId discordId);

    /** Returns every admin with the granter, root first, then in grant order. */
    List<Admin> admins();

    /** One admin and the granter, which is null for the root. */
    record Admin(DiscordId discordId, String grantedBy, Instant grantedAt) {}

    /** The result of {@link #grant(String, String)}. */
    enum Grant {
        GRANTED,
        /** The granter is not an admin. */
        ACTOR_NOT_ADMIN,
        ALREADY_ADMIN,
        /** The target is not a member of the guild as the bot last saw it, or the bot does not know it. */
        NOT_A_MEMBER,
        /** {@value AdminTree#GRANTS_PER_HOUR} grants were made in the last hour. */
        RATE_LIMITED
    }

    /**
     * The result of {@link #revoke(String, String)}.
     *
     * @param removed every account that stopped being an admin, the target first
     */
    record Revocation(Outcome outcome, List<String> removed) {

        public enum Outcome {
            REVOKED,
            ACTOR_NOT_ADMIN,
            /** Nobody revokes themselves, not even the root. */
            SELF,
            /** The target is not strictly below the revoker, or is no admin. */
            NOT_BELOW
        }

        static Revocation refused(final Outcome outcome) {
            return new Revocation(outcome, List.of());
        }
    }
}
