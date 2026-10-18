package eu.nordtal.season.database.access;

import javax.sql.DataSource;

/**
 * Which players an admin lets join without the resource pack.
 *
 * An exemption lasts until an admin enforces the pack again and takes effect at the next login.
 */
public interface PackExemptions {

    /** Returns exemptions that borrow {@code dataSource}. */
    static PackExemptions using(final DataSource dataSource) {
        return new JdbiPackExemptions(dataSource);
    }

    /**
     * {@code actor} lets {@code target} play without the resource pack.
     *
     * @return what happened. Nothing is written unless it is {@link Outcome#CHANGED}
     */
    Outcome exempt(String actor, String target);

    /**
     * {@code actor} makes the resource pack required for {@code target} again.
     *
     * @return what happened. Nothing is written unless it is {@link Outcome#CHANGED}
     */
    Outcome enforce(String actor, String target);

    /** The result of an exemption or an enforcement. */
    enum Outcome {
        /** Written. */
        CHANGED,
        /** The account is in that state already. */
        UNCHANGED,
        /** The actor is no longer an admin. */
        ACTOR_NOT_ADMIN,
        /** Nobody has that Discord id. */
        UNKNOWN
    }
}
